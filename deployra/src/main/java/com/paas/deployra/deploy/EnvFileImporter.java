package com.paas.deployra.deploy;

import com.paas.deployra.common.DotEnvParser;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.EnvVariableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Đọc {@code .env.example} và {@code .env} trong thư mục mã nguồn (hoặc ngay
 * trong file zip vừa upload), chỉ lấy
 * <b>tên biến</b> rồi tạo các biến <b>còn thiếu</b> cho service với giá trị
 * rỗng. Giá trị trong file (cả {@code .env} lẫn {@code .env.example}) không
 * bao giờ được lấy, lưu hay log; người dùng tự điền giá trị sau.
 *
 * <ul>
 * <li>Không ghi đè biến đã có trong DB.</li>
 * <li>Bỏ qua symlink, file quá lớn, dòng không hợp lệ.</li>
 * <li>Log chỉ ghi <b>tên biến</b>, không bao giờ ghi giá trị.</li>
 * <li>Không có file thì không làm gì (Outcome zero).</li>
 * <li>Không ném exception ra ngoài — pipeline deploy coi đây là bước
 * best-effort.</li>
 * </ul>
 */
@Component
public class EnvFileImporter {

    private static final Logger log = LoggerFactory.getLogger(EnvFileImporter.class);

    /** Giới hạn kích thước file .env — file lớn hơn coi như bất thường. */
    static final long MAX_FILE_BYTES = 1024 * 1024; // 1 MB

    /** Các file được đọc; key của cả hai file được gộp lại. */
    static final List<String> CANDIDATE_FILES = List.of(".env.example", ".env");

    /**
     * Khi quét zip lúc upload: dừng nếu quá nhiều mục hoặc tổng dung lượng giải
     * nén quá lớn (chống zip bomb). Dừng thì bỏ qua bước này, biến vẫn được import
     * khi deploy.
     */
    static final int MAX_SCAN_ENTRIES = 20_000;
    static final long MAX_SCAN_BYTES = 256L * 1024 * 1024;

    /** Giá trị đặt cho biến mới tạo: luôn rỗng, vì không lấy value từ file. */
    static final String EMPTY_VALUE = "";

    private final EnvVariableService envVars;

    public EnvFileImporter(EnvVariableService envVars) {
        this.envVars = envVars;
    }

    public record Outcome(int created, int existing, int ignored) {
    }

    public Outcome importInto(AppService service, Path sourceDir, Consumer<String> logLine) {
        if (service == null || service.getId() == null
                || sourceDir == null || !Files.isDirectory(sourceDir)) {
            return new Outcome(0, 0, 0);
        }

        // 1. Đọc & gộp tên biến của các file (chỉ key, không có value)
        Set<String> merged = new LinkedHashSet<>();
        int ignored = 0;
        for (String name : CANDIDATE_FILES) {
            Parsed parsed = readAndParse(sourceDir.resolve(name), logLine);
            ignored += parsed.ignored;
            merged.addAll(parsed.keys);
        }
        if (merged.isEmpty()) {
            return new Outcome(0, 0, ignored);
        }

        return createMissing(service, merged, ignored, logLine);
    }

    /**
     * Đọc {@code .env.example}/{@code .env} ngay trong file zip (chưa giải nén ra
     * đĩa), chỉ lấy tên biến rồi tạo các biến còn thiếu. Dùng ngay sau khi upload
     * để người dùng thấy key và điền giá trị trước khi deploy.
     *
     * <p>
     * Tìm đúng chỗ mà {@code ZipSourceExtractor} sẽ đặt file sau khi giải nén: ở
     * gốc,
     * hoặc trong thư mục bọc nếu cả zip nằm gọn trong đúng một thư mục
     * ({@code repo-main/...}). Không ném exception: zip lỗi hoặc quá lớn thì bỏ
     * qua,
     * deploy sẽ báo lỗi rõ ràng sau.
     */
    public Outcome importFromZip(AppService service, InputStream zip, Consumer<String> logLine) {
        if (service == null || service.getId() == null || zip == null) {
            return new Outcome(0, 0, 0);
        }
        Parsed parsed = scanZip(zip, logLine);
        if (parsed.keys.isEmpty()) {
            return new Outcome(0, 0, parsed.ignored);
        }
        return createMissing(service, parsed.keys, parsed.ignored, logLine);
    }

    /** So tên biến với DB và tạo các biến còn thiếu (giá trị rỗng). */
    private Outcome createMissing(AppService service, Set<String> keys, int ignored, Consumer<String> logLine) {
        Set<String> existingKeys = new HashSet<>(envVars.keysOf(service.getId()));
        List<String> createdNames = new ArrayList<>();
        int existing = 0;

        for (String key : keys) {
            if (existingKeys.contains(key)) {
                existing++;
                continue;
            }
            try {
                envVars.set(service, key, EMPTY_VALUE);
                existingKeys.add(key); // chặn trùng nếu file khác cũng khai báo key này
                createdNames.add(key);
            } catch (RuntimeException ex) {
                // Tên biến không hợp lệ, hoặc lỗi mã hoá/lưu — bỏ qua, không làm fail deploy
                ignored++;
                log.warn("EnvFileImporter: bỏ qua biến '{}' của service {} — {}",
                        key, service.getId(), ex.getMessage());
            }
        }

        if (logLine != null && !createdNames.isEmpty()) {
            logLine.accept("Đã tạo " + createdNames.size()
                    + " biến môi trường từ .env (chỉ tên, giá trị để trống, cần tự điền): "
                    + String.join(", ", createdNames));
        }

        return new Outcome(createdNames.size(), existing, ignored);
    }

    // ---------- Đọc zip ----------

    private Parsed scanZip(InputStream in, Consumer<String> logLine) {
        // đường dẫn đã chuẩn hoá ("a/.env") -> tên biến trong file đó
        Map<String, Set<String>> found = new HashMap<>();
        Set<String> topNames = new HashSet<>();
        boolean topLevelFile = false;
        int ignored = 0;
        int entries = 0;
        long scanned = 0;
        byte[] buffer = new byte[16 * 1024];

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_SCAN_ENTRIES) {
                    return skippedScan(logLine, "zip có quá nhiều mục");
                }
                String rawName = entry.getName();
                List<String> parts = normalize(rawName);
                if (parts == null || parts.isEmpty()) {
                    continue; // tên lạ, rác hệ điều hành, hoặc entry gốc rỗng
                }
                boolean isDir = entry.isDirectory() || rawName.endsWith("\\");
                topNames.add(parts.get(0));
                if (!isDir && parts.size() == 1) {
                    topLevelFile = true;
                }

                boolean candidate = !isDir && ((parts.size() == 1 && CANDIDATE_FILES.contains(parts.get(0)))
                        || (parts.size() == 2 && CANDIDATE_FILES.contains(parts.get(1))));

                ByteArrayOutputStream keep = candidate ? new ByteArrayOutputStream() : null;
                boolean tooBig = false;
                int n;
                while ((n = zis.read(buffer)) != -1) {
                    scanned += n;
                    if (scanned > MAX_SCAN_BYTES) {
                        return skippedScan(logLine, "zip giải nén quá lớn");
                    }
                    if (keep != null && !tooBig) {
                        if (keep.size() + n > MAX_FILE_BYTES) {
                            tooBig = true;
                        } else {
                            keep.write(buffer, 0, n);
                        }
                    }
                }
                if (!candidate) {
                    continue;
                }
                String fileName = parts.get(parts.size() - 1);
                if (tooBig) {
                    ignored++;
                    log.warn("EnvFileImporter: bỏ qua {} trong zip: file quá lớn", fileName);
                    if (logLine != null) {
                        logLine.accept("Bỏ qua " + fileName + " trong zip: file quá lớn");
                    }
                    continue;
                }
                DotEnvParser.Result r = DotEnvParser.parse(keep.toString(StandardCharsets.UTF_8));
                ignored += r.skipped().size();
                found.merge(String.join("/", parts), new LinkedHashSet<>(r.keys()), (x, y) -> {
                    x.addAll(y);
                    return x;
                });
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("EnvFileImporter: không quét được zip — {}", ex.getMessage());
            return new Parsed(Set.of(), ignored + 1);
        }

        // Giống ZipSourceExtractor.flattenSingleRoot: cả zip nằm trong đúng một thư mục
        // (và không có file nào ở gốc) thì nội dung được đưa lên một cấp.
        boolean flatten = topNames.size() == 1 && !topLevelFile;
        String prefix = flatten ? topNames.iterator().next() + "/" : "";
        Set<String> keys = new LinkedHashSet<>();
        for (String name : CANDIDATE_FILES) {
            Set<String> k = found.get(prefix + name);
            if (k != null) {
                keys.addAll(k);
            }
        }
        return new Parsed(keys, ignored);
    }

    private Parsed skippedScan(Consumer<String> logLine, String reason) {
        log.warn("EnvFileImporter: bỏ qua quét .env trong zip: {}", reason);
        if (logLine != null) {
            logLine.accept("Bỏ qua quét .env trong zip (" + reason + "); biến sẽ được import khi deploy");
        }
        return new Parsed(Set.of(), 1);
    }

    /**
     * Chuẩn hoá tên entry giống {@code ZipSourceExtractor.safeParts} nhưng không
     * ném
     * lỗi: trả về null nếu entry nguy hiểm hoặc là rác (đường dẫn tuyệt đối, "..",
     * tên
     * quá dài, {@code __MACOSX}, {@code .DS_Store}, {@code Thumbs.db}).
     */
    private static List<String> normalize(String rawName) {
        if (rawName == null || rawName.length() > 1024 || rawName.indexOf('\0') >= 0) {
            return null;
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null;
            }
            parts.add(segment);
        }
        if (!parts.isEmpty()) {
            String first = parts.get(0);
            String last = parts.get(parts.size() - 1);
            if (first.equals("__MACOSX") || last.equals(".DS_Store") || last.equalsIgnoreCase("Thumbs.db")) {
                return null;
            }
        }
        return parts;
    }

    // ---------- Đọc file ----------

    private record Parsed(Set<String> keys, int ignored) {
    }

    private Parsed readAndParse(Path file, Consumer<String> logLine) {
        // Không tồn tại: không phải lỗi, chỉ là không có gì để làm
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Parsed(Set.of(), 0);
        }
        // Symlink: bỏ qua để tránh đọc ra ngoài thư mục mã nguồn
        if (Files.isSymbolicLink(file)) {
            log.warn("EnvFileImporter: bỏ qua symlink {}", file.getFileName());
            if (logLine != null) {
                logLine.accept("Bỏ qua " + file.getFileName() + ": là symlink");
            }
            return new Parsed(Set.of(), 1);
        }
        if (!Files.isRegularFile(file)) {
            return new Parsed(Set.of(), 0);
        }

        // Kích thước
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                log.warn("EnvFileImporter: bỏ qua {} quá lớn ({} byte > {})",
                        file.getFileName(), size, MAX_FILE_BYTES);
                if (logLine != null) {
                    logLine.accept("Bỏ qua " + file.getFileName()
                            + ": file quá lớn (" + size + " byte)");
                }
                return new Parsed(Set.of(), 1);
            }
        } catch (IOException ex) {
            log.warn("EnvFileImporter: không đọc size {} — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Set.of(), 1);
        }

        // Đọc & parse
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            DotEnvParser.Result r = DotEnvParser.parse(text);
            return new Parsed(r.keys(), r.skipped().size());
        } catch (IOException ex) {
            log.warn("EnvFileImporter: không đọc được {} — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Set.of(), 1);
        } catch (RuntimeException ex) {
            log.warn("EnvFileImporter: parse {} lỗi — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Set.of(), 1);
        }
    }
}