package com.paas.deployra.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Đọc nội dung file .env và chỉ lấy <b>tên biến (key)</b>. Giá trị (value)
 * không bao giờ được giữ lại, trả về, lưu hay log. Thuần JDK, không phụ thuộc
 * Spring.
 *
 * <p>
 * Parser vẫn phải đi qua phần value để biết dòng nào là một biến thật, nếu
 * không các dòng nằm trong value nhiều dòng (ví dụ private key) có thể bị nhận
 * nhầm thành key. Hỗ trợ:
 * </p>
 * <ul>
 * <li>Comment dòng {@code #...}</li>
 * <li>{@code export KEY=value}</li>
 * <li>Value nháy kép {@code "..."}: cho phép trải nhiều dòng, nháy kép bên
 * trong escape bằng {@code \"}</li>
 * <li>Value nháy đơn {@code '...'}: literal, cho phép trải nhiều dòng</li>
 * <li>Value không nháy: cắt comment {@code  #...} ở cuối</li>
 * <li><b>Không</b> nội suy {@code ${...}}</li>
 * </ul>
 *
 * <p>
 * Dòng không parse được (thiếu {@code =}, tên biến sai, nháy không đóng, có ký
 * tự thừa sau nháy đóng) → thêm {@code "dòng N"} vào {@code skipped}. Không lưu
 * nội dung dòng vì có thể chứa secret.
 * </p>
 */
public final class DotEnvParser {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DotEnvParser() {
    }

    /**
     * {@code keys}: tên biến theo thứ tự xuất hiện, đã loại trùng. Không có value.
     */
    public record Result(Set<String> keys, List<String> skipped) {
    }

    public static Result parse(String text) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>();
        if (text == null || text.isEmpty())
            return new Result(keys, skipped);

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            int lineNo = i + 1;
            String raw = lines[i++];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;

            String content = trimmed;
            if (content.startsWith("export ") || content.startsWith("export\t")) {
                content = content.substring("export".length()).trim();
            }

            int eq = content.indexOf('=');
            if (eq <= 0) {
                skipped.add("dòng " + lineNo);
                continue;
            }

            String key = content.substring(0, eq).trim();
            if (!KEY_PATTERN.matcher(key).matches()) {
                skipped.add("dòng " + lineNo);
                continue;
            }

            String valuePart = content.substring(eq + 1).trim();

            // Ghép dòng tiếp theo nếu value mở nháy mà chưa đóng (chỉ để biết value
            // kết thúc ở đâu, nội dung không được dùng)
            if (valuePart.startsWith("\"")) {
                while (!hasClosingDouble(valuePart) && i < lines.length) {
                    valuePart = valuePart + "\n" + lines[i++];
                }
            } else if (valuePart.startsWith("'")) {
                while (countChar(valuePart, '\'') < 2 && i < lines.length) {
                    valuePart = valuePart + "\n" + lines[i++];
                }
            }

            try {
                checkValueSyntax(valuePart); // chỉ kiểm tra cú pháp, bỏ nội dung
                keys.add(key);
            } catch (IllegalArgumentException ex) {
                skipped.add("dòng " + lineNo);
            }
        }
        return new Result(keys, skipped);
    }

    // ---------- kiểm tra cú pháp value (không trả về nội dung) ----------

    private static void checkValueSyntax(String raw) {
        if (raw.isEmpty())
            return;

        char first = raw.charAt(0);

        if (first == '"') {
            int end = findClosingDouble(raw);
            if (end < 0)
                throw new IllegalArgumentException("unclosed double quote");
            requireOnlyCommentAfter(raw.substring(end + 1));
            return;
        }

        if (first == '\'') {
            int end = raw.lastIndexOf('\'');
            if (end <= 0)
                throw new IllegalArgumentException("unclosed single quote");
            requireOnlyCommentAfter(raw.substring(end + 1));
        }
        // không nháy: luôn hợp lệ
    }

    private static void requireOnlyCommentAfter(String trailing) {
        String t = trailing.trim();
        if (!t.isEmpty() && !t.startsWith("#")) {
            throw new IllegalArgumentException("trailing chars after quote");
        }
    }

    private static int findClosingDouble(String s) {
        boolean escaped = false;
        for (int k = 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"')
                return k;
        }
        return -1;
    }

    private static boolean hasClosingDouble(String s) {
        return findClosingDouble(s) >= 0;
    }

    private static int countChar(String s, char target) {
        int n = 0;
        for (int k = 0; k < s.length(); k++)
            if (s.charAt(k) == target)
                n++;
        return n;
    }
}