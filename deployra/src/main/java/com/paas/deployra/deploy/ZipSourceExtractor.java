package com.paas.deployra.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZipSourceExtractor {

    public record Result(Path directory, int fileCount, long totalBytes) {
    }

    private static final int MAX_DEPTH = 40;
    private static final int MAX_SEGMENT_BYTES = 255;
    private static final int MAX_NAME_LENGTH = 1024;
    private static final String FLATTEN_TEMP = ".deployra-root-tmp";

    private final int maxEntries;
    private final long maxFileBytes;
    private final long maxTotalBytes;

    @Autowired
    public ZipSourceExtractor(
            @Value("${deployra.zip.max-entries:10000}") int maxEntries,
            @Value("${deployra.zip.max-file-bytes:104857600}") long maxFileBytes,
            @Value("${deployra.zip.max-total-bytes:524288000}") long maxTotalBytes) {
        this.maxEntries = maxEntries;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public Result extract(InputStream zip, Path destination) {
        FileTree.prepareEmptyDirectory(destination);
        Path root = destination.toAbsolutePath().normalize();
        try {
            Result result = unpack(zip, root);
            flattenSingleRoot(root);
            return new Result(destination, result.fileCount(), result.totalBytes());
        } catch (RuntimeException e) {
            FileTree.deleteContents(root);
            throw e;
        }
    }

    private Result unpack(InputStream in, Path root) {
        int entries = 0;
        int files = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[16 * 1024];

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw new SourceFetchException("File zip có quá nhiều mục (tối đa " + maxEntries + ")");
                }
                List<String> parts = safeParts(entry.getName());
                if (parts == null) {
                    continue; // rác hệ điều hành, bỏ qua
                }
                if (parts.isEmpty()) {
                    continue; // entry gốc "./" hoặc "/" rỗng
                }
                String relative = String.join("/", parts);
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new SourceFetchException("File zip chứa đường dẫn không hợp lệ: " + shorten(entry.getName()));
                }

                if (entry.isDirectory() || entry.getName().endsWith("\\")) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!seen.add(relative.toLowerCase(Locale.ROOT))) {
                    throw new SourceFetchException("File zip có hai mục trùng tên: " + shorten(relative));
                }
                Files.createDirectories(target.getParent());

                long fileBytes = 0;
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    int n;
                    while ((n = zis.read(buffer)) != -1) {
                        fileBytes += n;
                        total += n;
                        if (fileBytes > maxFileBytes) {
                            throw new SourceFetchException("Một file trong zip quá lớn sau khi giải nén (tối đa "
                                    + mb(maxFileBytes) + " MB): " + shorten(relative));
                        }
                        if (total > maxTotalBytes) {
                            throw new SourceFetchException("Mã nguồn sau khi giải nén quá lớn (tối đa "
                                    + mb(maxTotalBytes) + " MB)");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                files++;
            }
        } catch (ZipException e) {
            throw new SourceFetchException("File zip không đọc được hoặc bị hỏng: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new SourceFetchException(
                    "File zip có tên mục không hợp lệ (sai mã hoá UTF-8 hoặc ký tự không cho phép)", e);
        } catch (IOException e) {
            throw new SourceFetchException("Không giải nén được file zip (cấu trúc thư mục xung đột hoặc lỗi ghi)", e);
        }

        if (files == 0) {
            throw new SourceFetchException("File zip rỗng hoặc không phải file zip");
        }
        return new Result(root, files, total);
    }

    /**
     * Chuẩn hoá tên entry thành danh sách thành phần đường dẫn an toàn.
     * Trả về null nếu entry là rác cần bỏ qua; ném lỗi nếu có dấu hiệu tấn công.
     */
    private List<String> safeParts(String rawName) {
        if (rawName == null || rawName.length() > MAX_NAME_LENGTH || rawName.indexOf('\0') >= 0) {
            throw new SourceFetchException("File zip chứa tên mục không hợp lệ");
        }
        String name = rawName.replace('\\', '/'); // zip tạo bằng một số công cụ Windows dùng dấu \
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new SourceFetchException("File zip chứa đường dẫn tuyệt đối: " + shorten(rawName));
        }
        List<String> parts = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new SourceFetchException("File zip chứa đường dẫn thoát ra ngoài (..): " + shorten(rawName));
            }
            if (segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                throw new SourceFetchException("File zip có tên thư mục/file quá dài");
            }
            parts.add(segment);
        }
        if (parts.size() > MAX_DEPTH) {
            throw new SourceFetchException("File zip có thư mục lồng quá sâu (tối đa " + MAX_DEPTH + " cấp)");
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

    /**
     * Zip dạng {@code repo-main/...}: toàn bộ nằm trong đúng một thư mục thì đưa
     * nội dung lên một cấp.
     */
    private void flattenSingleRoot(Path root) {
        try {
            Path only;
            try (Stream<Path> children = Files.list(root)) {
                List<Path> list = children.toList();
                if (list.size() != 1 || !Files.isDirectory(list.get(0), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                only = list.get(0);
            }
            // đổi tên trước để tránh trùng khi thư mục con cùng tên thư mục gốc
            // (app/app/...)
            Path temp = root.resolve(FLATTEN_TEMP);
            Files.move(only, temp);
            try (Stream<Path> inner = Files.list(temp)) {
                for (Path child : inner.toList()) {
                    Files.move(child, root.resolve(child.getFileName().toString()));
                }
            }
            Files.delete(temp);
        } catch (IOException e) {
            throw new SourceFetchException("Không sắp xếp được thư mục sau khi giải nén", e);
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String shorten(String s) {
        String clean = s.replace("\0", "?");
        return clean.length() <= 80 ? clean : clean.substring(0, 80) + "...";
    }
}