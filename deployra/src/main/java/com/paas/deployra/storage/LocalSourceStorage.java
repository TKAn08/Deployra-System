package com.paas.deployra.storage;

import com.paas.deployra.paas.SourceUpload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

@Component
public class LocalSourceStorage implements SourceStorage {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,299}");

    private final Path root;

    public LocalSourceStorage(@Value("${deployra.storage.local-dir:./data/uploads}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public SourceUpload.StorageBackend backend() {
        return SourceUpload.StorageBackend.LOCAL;
    }

    @Override
    public void put(String objectKey, InputStream data, long sizeBytes) {
        Path target = resolve(objectKey);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            long written;
            try (OutputStream out = Files.newOutputStream(temp)) {
                written = data.transferTo(out);
            }
            if (sizeBytes >= 0 && written != sizeBytes) {
                throw new StorageException("Dung lượng nhận được (" + written + " byte) không khớp với khai báo ("
                        + sizeBytes + " byte)");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException e) {
            throw new StorageException("Không ghi được file vào kho lưu trữ", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // file tạm sót lại không ảnh hưởng đúng sai, bỏ qua
                }
            }
        }
    }

    @Override
    public InputStream open(String objectKey) {
        Path file = resolve(objectKey);
        try {
            return Files.newInputStream(file);
        } catch (NoSuchFileException e) {
            throw new StorageException("Không tìm thấy file trong kho lưu trữ: " + objectKey, e);
        } catch (IOException e) {
            throw new StorageException("Không đọc được file trong kho lưu trữ", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new StorageException("Không xoá được file trong kho lưu trữ", e);
        }
    }

    /**
     * Chỉ chấp nhận key an toàn và bảo đảm đường dẫn cuối cùng nằm trong thư mục
     * gốc.
     */
    private Path resolve(String objectKey) {
        if (objectKey == null || !KEY.matcher(objectKey).matches() || objectKey.contains("..")
                || objectKey.contains("//") || objectKey.endsWith("/")
                || objectKey.contains("/./") || objectKey.endsWith("/.")) {
            throw new StorageException("Key không hợp lệ: " + objectKey);
        }
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new StorageException("Key không hợp lệ: " + objectKey);
        }
        return path;
    }
}