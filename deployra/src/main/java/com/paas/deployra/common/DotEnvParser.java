package com.paas.deployra.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Đọc nội dung file .env thành Map. Thuần JDK, không phụ thuộc Spring.
 *
 * <p>
 * Hỗ trợ:
 * </p>
 * <ul>
 * <li>Comment dòng {@code #...}</li>
 * <li>{@code export KEY=value}</li>
 * <li>Value nháy kép {@code "..."}: escape {@code \n \t \r \" \\}, cho phép
 * trải nhiều dòng</li>
 * <li>Value nháy đơn {@code '...'}: literal, cho phép trải nhiều dòng</li>
 * <li>Value không nháy: cắt comment {@code  #...} ở cuối</li>
 * <li><b>Không</b> nội suy {@code ${...}}</li>
 * </ul>
 *
 * <p>
 * Dòng không parse được → thêm {@code "dòng N"} vào {@code skipped}.
 * Không lưu nội dung dòng vì có thể chứa secret.
 * </p>
 */
public final class DotEnvParser {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DotEnvParser() {
    }

    public record Result(Map<String, String> values, List<String> skipped) {
    }

    public static Result parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        if (text == null || text.isEmpty())
            return new Result(values, skipped);

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

            // Ghép dòng tiếp theo nếu value mở nháy mà chưa đóng
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
                values.put(key, parseValue(valuePart));
            } catch (IllegalArgumentException ex) {
                skipped.add("dòng " + lineNo);
            }
        }
        return new Result(values, skipped);
    }

    // ---------- value ----------

    private static String parseValue(String raw) {
        if (raw.isEmpty())
            return "";

        char first = raw.charAt(0);

        if (first == '"') {
            int end = findClosingDouble(raw);
            if (end < 0)
                throw new IllegalArgumentException("unclosed double quote");
            String inner = raw.substring(1, end);
            String trailing = raw.substring(end + 1).trim();
            if (!trailing.isEmpty() && !trailing.startsWith("#")) {
                throw new IllegalArgumentException("trailing chars after quote");
            }
            return unescapeDouble(inner);
        }

        if (first == '\'') {
            int end = raw.lastIndexOf('\'');
            if (end <= 0)
                throw new IllegalArgumentException("unclosed single quote");
            String trailing = raw.substring(end + 1).trim();
            if (!trailing.isEmpty() && !trailing.startsWith("#")) {
                throw new IllegalArgumentException("trailing chars after quote");
            }
            return raw.substring(1, end); // literal
        }

        // unquoted: cắt comment tại ' #' hoặc '\t#'
        int cut = raw.length();
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c == '#' && (k == 0 || Character.isWhitespace(raw.charAt(k - 1)))) {
                cut = k;
                break;
            }
        }
        return raw.substring(0, cut).trim();
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

    private static String unescapeDouble(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length()) {
                char n = s.charAt(++k);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}