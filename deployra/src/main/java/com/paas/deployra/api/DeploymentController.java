package com.paas.deployra.api;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.deploy.DeploymentQueryService;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.SourceUploadService;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DeploymentQueryService query;

    public DeploymentController(SourceUploadService uploads, DeploymentQueryService query) {
        this.uploads = uploads;
        this.query = query;
    }

    @PostMapping
    public ResponseEntity<DeployResponse> deploy(@PathVariable Long serviceId,
            @RequestBody(required = false) DeployRequest request) {
        Long uploadId = request == null ? null : request.uploadId();
        if (uploadId != null && uploadId <= 0) {
            throw new InvalidRequestException("uploadId không hợp lệ");
        }
        Deployment deployment = (uploadId != null)
                ? uploads.deployUpload(3L, serviceId, uploadId)
                : uploads.deployFromGit(null, serviceId);
        return ResponseEntity.accepted().body(DeployResponse.from(deployment));
    }

    @GetMapping
    public List<DeploymentQueryService.Summary> list(@PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        return query.list(userId, serviceId);
    }

    /**
     * Trạng thái và log của một deployment. Frontend hỏi lặp lại, mỗi lần gửi
     * {@code offset = nextOffset} của lần trước để chỉ nhận phần log mới, và dừng
     * khi
     * {@code finished} là true và {@code moreLog} là false.
     */
    @GetMapping("/{deploymentId}")
    public DeploymentQueryService.Detail get(@PathVariable Long serviceId,
            @PathVariable Long deploymentId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int offset) {
        return query.get(userId, serviceId, deploymentId, offset);
    }
}