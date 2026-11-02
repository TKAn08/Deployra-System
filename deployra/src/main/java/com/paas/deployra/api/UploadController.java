package com.paas.deployra.api;

import com.paas.deployra.paas.SourceUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/**
 * {@code POST /api/services/{serviceId}/uploads}: upload file zip mã nguồn cho
 * một service và xếp hàng deploy.
 * Controller chỉ lấy người dùng và file từ request; mọi kiểm tra và điều phối
 * nằm trong {@link SourceUploadService}.
 *
 * <p>
 * Gọi thử:
 * {@code curl -u email:matkhau -F file=@app.zip localhost:8080/api/services/1/uploads}
 */
@RestController
@RequestMapping("/api/services/{serviceId}/uploads")
public class UploadController {

    public record UploadResponse(Long uploadId, Long deploymentId, String deploymentStatus,
            long sizeBytes, String sha256, Instant expiresAt) {

        static UploadResponse from(SourceUploadService.Result result) {
            return new UploadResponse(
                    result.upload().getId(),
                    result.deployment().getId(),
                    result.deployment().getStatus().name(),
                    result.upload().getSizeBytes(),
                    result.upload().getSha256(),
                    result.upload().getExpiresAt());
        }
    }

    private final SourceUploadService uploads;
    // private final CurrentUser currentUser;

    public UploadController(SourceUploadService uploads) {
        this.uploads = uploads;
        // this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file) throws IOException {
        SourceUploadService.Result result;
        try (InputStream in = file.getInputStream()) {
            result = uploads.uploadZip(null, serviceId, file.getOriginalFilename(), file.getSize(), in);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(result));
    }
}