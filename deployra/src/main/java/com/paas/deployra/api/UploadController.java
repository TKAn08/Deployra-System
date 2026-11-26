package com.paas.deployra.api;

import com.paas.deployra.deploy.RepoCheckService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.SourceUpload;
import com.paas.deployra.paas.SourceUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final RepoCheckService repoCheck;

    public UploadController(SourceUploadService uploads, RepoCheckService repoCheck) {
        this.uploads = uploads;
        this.repoCheck = repoCheck;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@PathVariable Long serviceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId)
            throws IOException {
        SourceUpload result;
        try (InputStream in = file.getInputStream()) {
            result = uploads.uploadZip(userId, serviceId, file.getOriginalFilename(), file.getSize(), in);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(result));
    }

    @PutMapping("/repo")
    public ResponseEntity<?> setRepo(@PathVariable Long serviceId, @RequestBody RepoRequest request) {
        RepoCheckService.Result check = repoCheck.check(request.repoUrl(), request.branch());
        if (!check.usable()) {
            HttpStatus status = switch (check.status()) {
                case INVALID_URL -> HttpStatus.BAD_REQUEST;
                case UNREACHABLE -> HttpStatus.BAD_GATEWAY;
                default -> HttpStatus.UNPROCESSABLE_ENTITY;
            };
            return ResponseEntity.status(status).body(check);
        }
        String branch = check.branch() != null ? check.branch() : check.defaultBranch();
        AppService saved = uploads.setRepo(null, serviceId, check.normalizedUrl(), branch);
        return ResponseEntity.ok(RepoResponse.from(saved));
    }

    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> deleteUpload(
            @PathVariable Long serviceId,
            @PathVariable Long uploadId) {
        uploads.deleteUpload(null, serviceId, uploadId);

        return ResponseEntity.noContent().build();
    }
}