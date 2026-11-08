package com.paas.deployra.paas;

import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.deploy.DeploymentServiceInterface;
import com.paas.deployra.deploy.EnvFileImporter;
import com.paas.deployra.deploy.GitSourceFetcher;
import com.paas.deployra.deploy.SourceFetchException;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.storage.SourceStorage;
import com.paas.deployra.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Lớp nằm giữa API và phần lưu/deploy. Hai việc tách riêng:
 *
 * <ul>
 * <li>{@link #uploadZip}: nhận và lưu file zip (kiểm quyền sở hữu service, kiểm
 * tra file, kiểm hạn mức, ghi vào kho, lưu dòng {@code source_uploads}). Chỉ
 * lưu,
 * <b>không</b> chạy.</li>
 * <li>{@link #setRepo}: lưu repo GitHub của service (cũng chỉ lưu, không
 * chạy).</li>
 * <li>{@link #deployUpload} / {@link #deployFromGit}: xếp hàng một deploy từ
 * zip đã
 * upload hoặc từ repo GitHub của service. Việc build và run do worker làm
 * nền.</li>
 * </ul>
 *
 * <p>
 * Cố ý <b>không</b> đặt {@code @Transactional} cho cả lớp: dòng
 * {@code source_uploads} phải commit trước khi
 * {@link DeploymentServiceInterface#enqueue}
 * chạy, nếu không worker có thể đọc không thấy upload.
 */
@Service
public class SourceUploadService {

    private static final Logger log = LoggerFactory.getLogger(SourceUploadService.class);

    /** Bốn byte đầu của file zip thông thường ("PK\3\4"). */
    private static final byte[] ZIP_MAGIC = { 'P', 'K', 3, 4 };

    private final AppServiceRepository services;
    private final SourceUploadRepository uploads;
    private final UserRepository users;
    private final SourceStorage storage;
    private final EntitlementService entitlements;
    private final DeploymentServiceInterface deployments;
    private final EnvFileImporter envFileImporter;
    private final GitSourceFetcher gitFetcher;
    private final Clock clock;
    private final Duration retention;

    public SourceUploadService(AppServiceRepository services,
            SourceUploadRepository uploads,
            UserRepository users,
            SourceStorage storage,
            EntitlementService entitlements,
            DeploymentServiceInterface deployments,
            EnvFileImporter envFileImporter,
            GitSourceFetcher gitFetcher,
            Clock clock,
            @Value("${deployra.upload.retention-days:30}") long retentionDays) {
        this.services = services;
        this.uploads = uploads;
        this.users = users;
        this.storage = storage;
        this.entitlements = entitlements;
        this.deployments = deployments;
        this.envFileImporter = envFileImporter;
        this.gitFetcher = gitFetcher;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
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
    public SourceUpload uploadZip(Long userId, Long serviceId, String filename, long sizeBytes, InputStream data) {
        AppService service = requireOwnedAppService(userId, serviceId);
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new InvalidRequestException("Chỉ nhận file .zip");
        }
        if (sizeBytes <= 0) {
            throw new InvalidRequestException("File rỗng");
        }
        entitlements.requireCanUpload(userId, sizeBytes);

        String key = "uploads/" + userId + "/" + UUID.randomUUID() + ".zip";
        String sha256 = store(key, sizeBytes, data);

        SourceUpload saved;
        try {
            SourceUpload upload = new SourceUpload();
            upload.setUser(users.getReferenceById(userId));
            upload.setStorageBackend(storage.backend());
            upload.setObjectKey(key);
            upload.setSizeBytes(sizeBytes);
            upload.setSha256(sha256);
            upload.setExpiresAt(clock.instant().plus(retention));
            saved = uploads.save(upload);
        } catch (RuntimeException e) {
            discard(key);
            throw e;
        }

        importEnvKeys(service, key);
        return saved;
    }

    /**
     * Đọc tên biến trong {@code .env}/{@code .env.example} của zip vừa lưu và tạo
     * biến
     * (giá trị rỗng) cho service. Best-effort: lỗi ở đây không được làm hỏng việc
     * upload đã thành công.
     */
    private void importEnvKeys(AppService service, String key) {
        try (InputStream in = storage.open(key)) {
            envFileImporter.importFromZip(service, in,
                    line -> log.info("Upload zip, service {}: {}", service.getId(), line));
        } catch (IOException | RuntimeException e) {
            log.warn("Upload zip, service {}: không import được biến môi trường — {}",
                    service.getId(), e.getMessage());
        }
    }

    /**
     * Xếp hàng deploy từ một file zip đã upload trước đó. Chỉ trả về deployment ở
     * trạng thái {@code QUEUED}; build và run chạy nền.
     */
    public Deployment deployUpload(Long userId, Long serviceId, Long uploadId) {
        AppService service = requireOwnedAppService(userId, serviceId);
        SourceUpload upload = uploads.findByIdAndUserIdAndDeletedAtIsNull(uploadId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy bản upload"));
        if (upload.getExpiresAt() != null && upload.getExpiresAt().isBefore(clock.instant())) {
            throw new InvalidRequestException("Bản upload đã hết hạn");
        }
        return deployments.enqueue(service, Deployment.Trigger.MANUAL, upload);
    }

    /**
     * Lưu repo GitHub cho service (thay thế cấu hình repo cũ). Chỉ lưu, không clone
     * hay chạy.
     *
     * @param branch null hoặc rỗng nghĩa là dùng branch mặc định của repo
     */
    public AppService setRepo(Long userId, Long serviceId, String repoUrl, String branch) {
        AppService service = requireOwnedAppService(userId, serviceId);
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new InvalidRequestException("Thiếu repoUrl");
        }
        String url;
        String safeBranch;
        try {
            // cùng bộ kiểm tra mà lúc deploy dùng: chỉ https, không user/mật khẩu,
            // chặn địa chỉ nội bộ (có phân giải DNS)
            url = gitFetcher.validateRepoUrl(repoUrl).toString();
            safeBranch = gitFetcher.requireBranch(branch);
        } catch (SourceFetchException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        service.setRepoUrl(url);
        service.setBranch(safeBranch);
        return services.save(service);
    }

    /** Xếp hàng deploy từ repo GitHub (repoUrl + branch) đang lưu trong service. */
    public Deployment deployFromGit(Long userId, Long serviceId) {
        AppService service = requireOwnedAppService(userId, serviceId);
        if (service.getRepoUrl() == null || service.getRepoUrl().isBlank()) {
            throw new InvalidRequestException("Service chưa cấu hình repo GitHub");
        }
        return deployments.enqueue(service, Deployment.Trigger.MANUAL);
    }

    private AppService requireOwnedAppService(Long userId, Long serviceId) {
        AppService service = services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
        if (service.getType() != AppService.Type.APP) {
            throw new InvalidRequestException("Service kiểu database không nhận mã nguồn");
        }
        return service;
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

    public void deleteUpload(Long userId, Long serviceId, Long uploadId) {

        requireOwnedAppService(userId, serviceId);

        SourceUpload upload = uploads
                .findByIdAndUserIdAndDeletedAtIsNull(uploadId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "Không tìm thấy bản upload"));

        upload.setDeletedAt(clock.instant());
        uploads.save(upload);

        try {
            storage.delete(upload.getObjectKey());
        } catch (RuntimeException e) {
            log.warn(
                    "Không thể xóa file upload {} khỏi storage: {}",
                    uploadId,
                    e.getMessage());
        }
    }

}