package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.api.dto.DeploymentDtos;
import com.paas.deployra.api.dto.ServiceDtos;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.DeploymentRepository;
import com.paas.deployra.paas.Project;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ServiceApiService {

    private final AppServiceRepository services;
    private final DeploymentRepository deployments;
    private final EntitlementService entitlements;
    private final ProjectApiService projects;
    private final CurrentUser currentUser;

    public ServiceApiService(AppServiceRepository services,
                             DeploymentRepository deployments,
                             EntitlementService entitlements,
                             ProjectApiService projects,
                             CurrentUser currentUser) {
        this.services = services;
        this.deployments = deployments;
        this.entitlements = entitlements;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @Transactional
    public ServiceDtos.Summary create(Long projectId, ServiceDtos.CreateRequest request) {
        User owner = currentUser.require();
        Project project = projects.requireOwned(projectId);
        AppService.Type type = request.type() == null ? AppService.Type.APP : request.type();
        int memoryMb = request.memoryMb() == null ? 256 : request.memoryMb();
        entitlements.requireCanCreateService(owner.getId(), type, memoryMb);

        AppService service = new AppService();
        service.setProject(project);
        service.setName(request.name().trim());
        service.setType(type);
        service.setRepoUrl(blankToNull(request.repoUrl()));
        if (request.branch() != null && !request.branch().isBlank()) {
            service.setBranch(request.branch().trim());
        }
        if (request.rootDir() != null && !request.rootDir().isBlank()) {
            service.setRootDir(request.rootDir().trim());
        }
        if (request.dockerfilePath() != null && !request.dockerfilePath().isBlank()) {
            service.setDockerfilePath(request.dockerfilePath().trim());
        }
        if (request.port() != null) {
            service.setPort(request.port());
        }
        if (request.healthcheckPath() != null && !request.healthcheckPath().isBlank()) {
            service.setHealthcheckPath(request.healthcheckPath().trim());
        }
        service.setMemoryMb(memoryMb);
        services.save(service);
        return toSummary(service);
    }

    @Transactional(readOnly = true)
    public ServiceDtos.Detail get(Long id) {
        AppService service = requireOwned(id);
        return new ServiceDtos.Detail(
                service.getId(),
                service.getProject().getId(),
                service.getProject().getName(),
                service.getName(),
                service.getSlug(),
                service.getType(),
                service.getStatus(),
                service.getRepoUrl(),
                service.getBranch(),
                service.getRootDir(),
                service.getDockerfilePath(),
                service.getPort(),
                service.getHealthcheckPath(),
                service.getMemoryMb(),
                service.getCpuMillicores(),
                service.getSuspendedReason(),
                service.getLastActiveAt(),
                service.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<DeploymentDtos.View> listDeployments(Long serviceId) {
        requireOwned(serviceId);
        return deployments.findByServiceIdOrderByCreatedAtDesc(serviceId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeploymentDtos.View latestDeployment(Long serviceId) {
        requireOwned(serviceId);
        List<Deployment> list = deployments.findByServiceIdOrderByCreatedAtDesc(serviceId);
        if (list.isEmpty()) {
            throw new ApiNotFoundException("Chưa có lần deploy nào");
        }
        return toView(list.getFirst());
    }

    /**
     * Ghi một deployment vào hàng đợi. Engine (Người 1) chưa gắn nên log ghi rõ trạng thái chờ.
     */
    @Transactional
    public DeploymentDtos.View enqueueDeploy(Long serviceId) {
        User owner = currentUser.require();
        AppService service = requireOwned(serviceId);
        entitlements.requireCanBuild(owner.getId());
        entitlements.requireCanStartService(owner.getId(), service);

        Deployment deployment = new Deployment();
        deployment.setService(service);
        deployment.setStatus(Deployment.Status.QUEUED);
        deployment.setTrigger(Deployment.Trigger.MANUAL);
        deployment.setSourceType(service.getRepoUrl() == null || service.getRepoUrl().isBlank()
                ? Deployment.SourceType.ZIP : Deployment.SourceType.GIT);
        deployment.setBuildLog("""
                [%s] Đã xếp hàng deploy cho service %s (%s).
                Deploy engine chưa được gắn — Người 1 sẽ chuyển QUEUED → BUILDING → LIVE.
                Frontend đang poll log mỗi 1,5 giây.
                """.formatted(Instant.now(), service.getName(), service.getSlug()));
        deployments.save(deployment);
        return toView(deployment);
    }

    private AppService requireOwned(Long id) {
        User owner = currentUser.require();
        AppService service = services.findById(id)
                .orElseThrow(() -> new ApiNotFoundException("Không tìm thấy service"));
        if (service.getDeletedAt() != null
                || service.getProject().getDeletedAt() != null
                || !service.getProject().getOwner().getId().equals(owner.getId())) {
            throw new ApiNotFoundException("Không tìm thấy service");
        }
        return service;
    }

    private ServiceDtos.Summary toSummary(AppService service) {
        return new ServiceDtos.Summary(
                service.getId(),
                service.getProject().getId(),
                service.getName(),
                service.getSlug(),
                service.getType(),
                service.getStatus(),
                service.getRepoUrl(),
                service.getBranch(),
                service.getPort(),
                service.getMemoryMb(),
                service.getCreatedAt());
    }

    private DeploymentDtos.View toView(Deployment deployment) {
        return new DeploymentDtos.View(
                deployment.getId(),
                deployment.getStatus(),
                deployment.getTrigger(),
                deployment.getSourceType(),
                deployment.getCommitSha(),
                deployment.getImage(),
                deployment.getBuildSeconds(),
                deployment.getBuildLog(),
                deployment.getCreatedAt(),
                deployment.getFinishedAt());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
