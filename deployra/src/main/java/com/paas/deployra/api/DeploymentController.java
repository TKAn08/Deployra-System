package com.paas.deployra.api;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.SourceUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{serviceId}/deployments")
public class DeploymentController {

    public record DeployRequest(Long uploadId) {
    }

    public record DeployResponse(Long deploymentId, String status, String sourceType) {
        static DeployResponse from(Deployment d) {
            return new DeployResponse(d.getId(), d.getStatus().name(), d.getSourceType().name());
        }
    }

    private final SourceUploadService uploads;

    public DeploymentController(SourceUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping
    public ResponseEntity<DeployResponse> deploy(@PathVariable Long serviceId,
            @RequestBody(required = false) DeployRequest request) {
        Long uploadId = request == null ? null : request.uploadId();
        if (uploadId != null && uploadId <= 0) {
            throw new InvalidRequestException("uploadId không hợp lệ");
        }
        Deployment deployment = (uploadId != null)
                ? uploads.deployUpload(null, serviceId, uploadId)
                : uploads.deployFromGit(null, serviceId);
        return ResponseEntity.accepted().body(DeployResponse.from(deployment));
    }
}