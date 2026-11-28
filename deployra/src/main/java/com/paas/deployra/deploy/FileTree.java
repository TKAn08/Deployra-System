package com.paas.deployra.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class FileTree {

    private FileTree() {
    }

    static void prepareEmptyDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Thư mục đích phải trống: " + dir);
                }
            }
        } catch (IOException e) {
            throw new SourceFetchException("Không tạo được thư mục làm việc", e);
        }
    }

    static long size(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            throw new SourceFetchException("Không đo được dung lượng thư mục", e);
        }
    }

    /**
     * Xoá cả thư mục (kể cả file chỉ-đọc của .git trên Windows). Không ném lỗi nếu
     * không tồn tại.
     */
    static void deleteRecursively(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // cố hết sức, phần còn lại do người gọi dọn
                }
            });
        } catch (IOException ignored) {
            // như trên
        }
    }

    /** Xoá mọi thứ bên trong thư mục nhưng giữ lại chính thư mục. */
    static void deleteContents(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(FileTree::deleteRecursively);
        } catch (IOException ignored) {
            // như trên
        }
    }
}