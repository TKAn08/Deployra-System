package com.paas.deployra.paas;

import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.deploy.DeploymentServiceInterface;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.storage.SourceStorage;
import com.paas.deployra.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Lớp nằm giữa API và phần lưu/deploy: điều phối việc nhận một file zip.
 *
 * <p>
 * Thứ tự: kiểm quyền sở hữu service, kiểm tra file, kiểm hạn mức, ghi vào kho,
 * lưu dòng
 * {@code source_uploads}, rồi xếp hàng deploy. Controller chỉ việc lấy
 * {@code userId} và file từ request
 * rồi gọi {@link #uploadZip}.
 *
 * <p>
 * Cố ý <b>không</b> đặt {@code @Transactional} cho cả lớp: dòng
 * {@code source_uploads} phải commit
 * trước khi {@link DeploymentServiceInterface#enqueue} chạy, nếu không worker
 * có thể đọc
 * không thấy upload.
 * Nếu bước sau lỗi thì file và dòng đã tạo được dọn lại ở đây.
 */
@Service
public class SourceUploadService {

    /** Bốn byte đầu của file zip thông thường ("PK\3\4"). */
    private static final byte[] ZIP_MAGIC = { 'P', 'K', 3, 4 };

    private final AppServiceRepository services;
    private final SourceUploadRepository uploads;
    private final UserRepository users;
    private final SourceStorage storage;
    private final EntitlementService entitlements;
    private final DeploymentServiceInterface deployments;
    private final Clock clock;
    private final Duration retention;

    public SourceUploadService(AppServiceRepository services,
            SourceUploadRepository uploads,
            UserRepository users,
            SourceStorage storage,
            EntitlementService entitlements,
            DeploymentServiceInterface deployments,
            Clock clock,
            @Value("${deployra.upload.retention-days:30}") long retentionDays) {
        this.services = services;
        this.uploads = uploads;
        this.users = users;
        this.storage = storage;
        this.entitlements = entitlements;
        this.deployments = deployments;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    public record Result(SourceUpload upload, Deployment deployment) {
    }

    /**
     * @param userId    người đang đăng nhập
     * @param serviceId service sẽ nhận mã nguồn
     * @param filename  tên file người dùng gửi lên (chỉ để kiểm đuôi {@code .zip},
     *                  không dùng làm tên lưu)
     * @param sizeBytes dung lượng thật của file (với Spring:
     *                  {@code MultipartFile.getSize()}), không phải số người dùng
     *                  tự khai
     * @param data      nội dung file
     */
    public Result uploadZip(Long userId, Long serviceId, String filename, long sizeBytes, InputStream data) {
        AppService service = services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
        if (service.getType() != AppService.Type.APP) {
            throw new InvalidRequestException("Service kiểu database không nhận mã nguồn");
        }
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new InvalidRequestException("Chỉ nhận file .zip");
        }
        if (sizeBytes <= 0) {
            throw new InvalidRequestException("File rỗng");
        }
        entitlements.requireCanUpload(userId, sizeBytes);

        String key = "uploads/" + userId + "/" + UUID.randomUUID() + ".zip";
        String sha256 = store(key, sizeBytes, data);

        SourceUpload upload;
        try {
            upload = new SourceUpload();
            upload.setUser(users.getReferenceById(userId));
            upload.setStorageBackend(storage.backend());
            upload.setObjectKey(key);
            upload.setSizeBytes(sizeBytes);
            upload.setSha256(sha256);
            upload.setExpiresAt(clock.instant().plus(retention));
            upload = uploads.save(upload);
        } catch (RuntimeException e) {
            discard(key);
            throw e;
        }

        try {
            Deployment deployment = deployments.enqueue(service, Deployment.Trigger.MANUAL, upload);
            return new Result(upload, deployment);
        } catch (RuntimeException e) {
            discard(key);
            try {
                upload.setDeletedAt(clock.instant());
                uploads.save(upload);
            } catch (RuntimeException inner) {
                e.addSuppressed(inner);
            }
            throw e;
        }
    }

    /** Kiểm tra đúng là zip, ghi vào kho và trả về SHA-256 của nội dung. */
    private String store(String key, long sizeBytes, InputStream data) {
        try {
            BufferedInputStream in = new BufferedInputStream(data);
            in.mark(ZIP_MAGIC.length);
            byte[] head = in.readNBytes(ZIP_MAGIC.length);
            if (!Arrays.equals(head, ZIP_MAGIC)) {
                throw new InvalidRequestException("File không phải zip hợp lệ");
            }
            in.reset();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            storage.put(key, new DigestInputStream(in, digest), sizeBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new StorageException("Không đọc được file upload", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }

    /**
     * Dọn file đã ghi khi bước sau lỗi. Lỗi khi dọn không được che lỗi gốc nên chỉ
     * bỏ qua.
     */
    private void discard(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // file sót lại sẽ bị job dọn zip hết hạn xử lý
        }
    }
}