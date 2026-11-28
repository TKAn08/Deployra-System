package com.paas.deployra.deploy;

import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.Limit;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.Deployment.Status;
import com.paas.deployra.paas.DeploymentRepository;
import com.paas.deployra.runtime.ContainerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dọn các Docker image cũ của một service theo hạn mức {@code deployments_retained}
 * của chủ sở hữu và đánh dấu {@code imagePruned = true} trong DB.
 */
@Service
public class ImagePruner {

    private static final Logger log = LoggerFactory.getLogger(ImagePruner.class);

    public record PruneResult(int prunedCount, List<String> removedImages) {
    }

    private final DeploymentRepository deployments;
    private final AppServiceRepository services;
    private final EntitlementService entitlements;
    private final ContainerRuntime runtime;
    private final TransactionTemplate tx;

    public ImagePruner(DeploymentRepository deployments,
                       AppServiceRepository services,
                       EntitlementService entitlements,
                       ContainerRuntime runtime,
                       TransactionTemplate tx) {
        this.deployments = deployments;
        this.services = services;
        this.entitlements = entitlements;
        this.runtime = runtime;
        this.tx = tx;
    }

    /**
     * Kiểm tra và dọn các image cũ vượt quá số lượng {@code deployments_retained}.
     * Luôn giữ lại image của deployment đang {@code LIVE} hoặc đang chạy ({@code QUEUED/BUILDING/DEPLOYING}).
     */
    public PruneResult pruneService(Long serviceId) {
        PrunePlan plan = tx.execute(status -> buildPlan(serviceId));
        if (plan == null || plan.candidates().isEmpty()) {
            return new PruneResult(0, List.of());
        }

        Set<String> protectedImages = new HashSet<>(plan.retainedImages());
        List<String> removedImages = new ArrayList<>();
        int prunedCount = 0;

        for (DeploymentCandidate candidate : plan.candidates()) {
            String image = candidate.image();
            boolean shouldDeleteFromDocker = !protectedImages.contains(image);

            if (shouldDeleteFromDocker) {
                try {
                    runtime.removeImage(image);
                    protectedImages.add(image); // tránh gọi removeImage trùng lặp nếu nhiều deployment dùng chung tag
                    removedImages.add(image);
                } catch (RuntimeException e) {
                    log.warn("Không xoá được image '{}' cho deployment {}: {}",
                            image, candidate.deploymentId(), e.getMessage());
                    continue;
                }
            }

            markPruned(candidate.deploymentId());
            prunedCount++;
        }

        return new PruneResult(prunedCount, removedImages);
    }

    private record DeploymentCandidate(Long deploymentId, String image) {
    }

    private record PrunePlan(Set<String> retainedImages, List<DeploymentCandidate> candidates) {
    }

    private PrunePlan buildPlan(Long serviceId) {
        AppService service = services.findById(serviceId)
                .filter(s -> s.getDeletedAt() == null)
                .orElse(null);
        if (service == null) {
            return null;
        }

        Long userId = service.getProject().getOwner().getId();
        Limit limit = entitlements.effectiveLimit(userId, EntitlementCodes.DEPLOYMENTS_RETAINED);
        if (limit.unlimited()) {
            return null;
        }

        long keepCount = Math.max(0, limit.value());
        List<Deployment> withImages = deployments.findByServiceIdOrderByCreatedAtDesc(serviceId).stream()
                .filter(d -> d.getImage() != null && !d.getImage().isBlank() && !d.isImagePruned())
                .toList();

        if (withImages.isEmpty()) {
            return null;
        }

        Set<Long> retainedIds = new HashSet<>();
        Set<String> retainedImages = new HashSet<>();

        // 1. Luôn giữ các deployment đang LIVE hoặc đang trong tiến trình deploy
        for (Deployment d : withImages) {
            if (isActiveOrInProgress(d.getStatus())) {
                retainedIds.add(d.getId());
                retainedImages.add(d.getImage());
            }
        }

        // 2. Giữ thêm các deployment mới nhất cho đến khi đủ hạn mức keepCount
        List<DeploymentCandidate> candidates = new ArrayList<>();
        for (Deployment d : withImages) {
            if (retainedIds.contains(d.getId())) {
                continue;
            }
            if (retainedIds.size() < keepCount) {
                retainedIds.add(d.getId());
                retainedImages.add(d.getImage());
            } else {
                candidates.add(new DeploymentCandidate(d.getId(), d.getImage()));
            }
        }

        return new PrunePlan(retainedImages, candidates);
    }

    private void markPruned(Long deploymentId) {
        tx.executeWithoutResult(status -> deployments.findById(deploymentId).ifPresent(d -> {
            d.setImagePruned(true);
            deployments.save(d);
        }));
    }

    private static boolean isActiveOrInProgress(Status status) {
        return status == Status.LIVE
                || status == Status.QUEUED
                || status == Status.BUILDING
                || status == Status.DEPLOYING;
    }
}
