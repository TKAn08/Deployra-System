package com.paas.deployra.api;

import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.SourceUpload;
import com.paas.deployra.paas.SourceUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/**
 * Nguồn mã của một service, hai cách đưa vào, <b>đều chỉ lưu, không chạy</b>:
 *
 * <ul>
 * <li>{@code POST /api/services/{serviceId}/uploads}: upload file zip.</li>
 * <li>{@code PUT /api/services/{serviceId}/repo}: lưu repo GitHub
 * ({@code {"repoUrl": "https://github.com/acme/demo.git", "branch": "main"}};
 * {@code branch} bỏ trống nghĩa là dùng branch mặc định của repo; gọi lại sẽ
 * thay
 * thế cấu hình cũ).</li>
 * </ul>
 *
 * Muốn chạy thì gọi tiếp {@code POST /api/services/{serviceId}/deployments}
 * (xem
 * {@link DeploymentController}). Controller chỉ lấy người dùng và dữ liệu từ
 * request; mọi kiểm tra và điều phối nằm trong {@link SourceUploadService}.
 *
 * <p>
 * Gọi thử:
 * {@code curl -u email:matkhau -F file=@app.zip localhost:8080/api/services/1/uploads}
 */
@RestController
@RequestMapping("/api/services/{serviceId}")
public class UploadController {

    public record UploadResponse(Long uploadId, long sizeBytes, String sha256, Instant expiresAt) {

        static UploadResponse from(SourceUpload upload) {
            return new UploadResponse(
                    upload.getId(),
                    upload.getSizeBytes(),
                    upload.getSha256(),
                    upload.getExpiresAt());
        }
    }

    public record RepoRequest(String repoUrl, String branch) {
    }

    public record RepoResponse(String repoUrl, String branch) {

        static RepoResponse from(AppService s) {
            return new RepoResponse(s.getRepoUrl(), s.getBranch());
        }
    }

    private final SourceUploadService uploads;
    // private final CurrentUser currentUser;

    public UploadController(SourceUploadService uploads) {
        this.uploads = uploads;
        // this.currentUser = currentUser;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file) throws IOException {
        SourceUpload result;
        try (InputStream in = file.getInputStream()) {
            result = uploads.uploadZip(null, serviceId, file.getOriginalFilename(), file.getSize(), in);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(result));
    }

    @PutMapping("/repo")
    public RepoResponse setRepo(@PathVariable Long serviceId, @RequestBody RepoRequest request) {
        AppService saved = uploads.setRepo(null, serviceId, request.repoUrl(), request.branch());
        return RepoResponse.from(saved);
    }
}