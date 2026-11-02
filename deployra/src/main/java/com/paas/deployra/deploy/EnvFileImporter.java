package com.paas.deployra.deploy;

import com.paas.deployra.common.DotEnvParser;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.EnvVariableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Đọc {@code .env.example} rồi {@code .env} (file sau ghi đè giá trị file
 * trước) trong
 * thư mục mã nguồn và tạo các biến môi trường <b>còn thiếu</b> cho service.
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

    /** Đọc theo thứ tự này; file sau ghi đè giá trị file trước. */
    static final List<String> CANDIDATE_FILES = List.of(".env.example", ".env");

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

        // 1. Đọc & gộp các file, file sau ghi đè file trước
        Map<String, String> merged = new LinkedHashMap<>();
        int ignored = 0;
        for (String name : CANDIDATE_FILES) {
            Parsed parsed = readAndParse(sourceDir.resolve(name), logLine);
            ignored += parsed.ignored;
            merged.putAll(parsed.values); // putAll = ghi đè theo thứ tự
        }
        if (merged.isEmpty()) {
            return new Outcome(0, 0, ignored);
        }

        // 2. So với biến đã có trong DB
        Set<String> existingKeys = new HashSet<>(envVars.keysOf(service.getId()));
        List<String> createdNames = new ArrayList<>();
        int existing = 0;

        for (Map.Entry<String, String> e : merged.entrySet()) {
            String key = e.getKey();
            if (existingKeys.contains(key)) {
                existing++;
                continue;
            }
            try {
                envVars.set(service, key, e.getValue());
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
                    + " biến môi trường từ .env: " + String.join(", ", createdNames));
        }

        return new Outcome(createdNames.size(), existing, ignored);
    }

    // ---------- Đọc file ----------

    private record Parsed(Map<String, String> values, int ignored) {
    }

    private Parsed readAndParse(Path file, Consumer<String> logLine) {
        // Không tồn tại: không phải lỗi, chỉ là không có gì để làm
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Parsed(Map.of(), 0);
        }
        // Symlink: bỏ qua để tránh đọc ra ngoài thư mục mã nguồn
        if (Files.isSymbolicLink(file)) {
            log.warn("EnvFileImporter: bỏ qua symlink {}", file.getFileName());
            if (logLine != null) {
                logLine.accept("Bỏ qua " + file.getFileName() + ": là symlink");
            }
            return new Parsed(Map.of(), 1);
        }
        if (!Files.isRegularFile(file)) {
            return new Parsed(Map.of(), 0);
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
                return new Parsed(Map.of(), 1);
            }
        } catch (IOException ex) {
            log.warn("EnvFileImporter: không đọc size {} — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Map.of(), 1);
        }

        // Đọc & parse
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            DotEnvParser.Result r = DotEnvParser.parse(text);
            return new Parsed(r.values(), r.skipped().size());
        } catch (IOException ex) {
            log.warn("EnvFileImporter: không đọc được {} — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Map.of(), 1);
        } catch (RuntimeException ex) {
            log.warn("EnvFileImporter: parse {} lỗi — {}", file.getFileName(), ex.getMessage());
            return new Parsed(Map.of(), 1);
        }
    }
}