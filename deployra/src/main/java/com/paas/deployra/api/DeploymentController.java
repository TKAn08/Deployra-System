package com.paas.deployra.api;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.deploy.DeploymentQueryService;
import com.paas.deployra.deploy.DeploymentService;
import com.paas.deployra.deploy.ImagePruner;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.SourceUploadService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
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

    public record PruneResponse(Long serviceId, int prunedCount, List<String> removedImages) {
    }

    private final SourceUploadService uploads;
    private final DeploymentQueryService query;
    private final DeploymentService deploymentService;
    private final ImagePruner imagePruner;

    @Autowired
    public DeploymentController(SourceUploadService uploads,
            DeploymentQueryService query,
            DeploymentService deploymentService,
            ImagePruner imagePruner) {
        this.uploads = uploads;
        this.query = query;
        this.deploymentService = deploymentService;
        this.imagePruner = imagePruner;
    }

    public DeploymentController(SourceUploadService uploads, DeploymentQueryService query) {
        this(uploads, query, null, null);
    }

    @PostMapping
    public ResponseEntity<DeployResponse> deploy(@PathVariable Long serviceId,
            @RequestParam(required = false) Long userId,
            @RequestBody(required = false) DeployRequest request) {
        Long uploadId = request == null ? null : request.uploadId();
        if (uploadId != null && uploadId <= 0) {
            throw new InvalidRequestException("uploadId không hợp lệ");
        }
        Deployment deployment = (uploadId != null)
                ? uploads.deployUpload(userId, serviceId, uploadId)
                : uploads.deployFromGit(userId, serviceId);
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

    /**
     * Rollback service về một deployment cũ (sử dụng lại image đã build, không
     * build lại).
     */
    @PostMapping("/{deploymentId}/rollback")
    public ResponseEntity<DeployResponse> rollback(@PathVariable Long serviceId,
            @PathVariable Long deploymentId,
            @RequestParam(required = false) Long userId) {
        if (deploymentId == null || deploymentId <= 0) {
            throw new InvalidRequestException("deploymentId không hợp lệ");
        }
        Deployment rollbackDeployment = deploymentService.rollbackById(userId, serviceId, deploymentId);
        return ResponseEntity.accepted().body(DeployResponse.from(rollbackDeployment));
    }

    /**
     * Dọn các image cũ của service vượt quá hạn mức {@code deployments_retained}.
     */
    @PostMapping("/prune")
    public PruneResponse prune(@PathVariable Long serviceId) {
        ImagePruner.PruneResult result = imagePruner.pruneService(serviceId);
        return new PruneResponse(serviceId, result.prunedCount(), result.removedImages());
    }
}