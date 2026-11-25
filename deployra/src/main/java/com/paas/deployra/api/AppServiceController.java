package com.paas.deployra.api;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.deploy.DatabaseProvisionService;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import com.paas.deployra.runtime.ContainerRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Controller quản lý Service và Vòng đời runtime của container (Start/Stop/Restart/Logs/Metrics)
 * theo phân công của Người 3 (API REST) và tích hợp các lệnh ContainerRuntime của Người 1.
 */
@RestController
public class AppServiceController {

    public record CreateServiceRequest(
            String name,
            AppService.Type type,
            Integer port,
            String rootDir,
            String dockerfilePath,
            Integer memoryMb,
            Integer cpuMillicores,
            String repoUrl,
            String branch
    ) {
    }

    public record UpdateServiceRequest(
            String name,
            Integer port,
            String branch,
            String rootDir,
            String dockerfilePath,
            Integer memoryMb,
            Integer cpuMillicores,
            String healthcheckPath
    ) {
    }

    public record ServiceResponse(
            Long id,
            Long projectId,
            String name,
            String slug,
            String type,
            String status,
            String accessUrl,
            Integer port,
            String healthcheckPath,
            int memoryMb,
            int cpuMillicores,
            String repoUrl,
            String branch,
            Instant lastActiveAt,
            Instant createdAt
    ) {
        public static ServiceResponse from(AppService s, String baseDomain) {
            String domain = (s.getSlug() != null && baseDomain != null && !baseDomain.isBlank())
                    ? "http://" + s.getSlug() + "." + baseDomain
                    : null;
            return new ServiceResponse(
                    s.getId(),
                    s.getProject() != null ? s.getProject().getId() : null,
                    s.getName(),
                    s.getSlug(),
                    s.getType().name(),
                    s.getStatus().name(),
                    domain,
                    s.getPort(),
                    s.getHealthcheckPath(),
                    s.getMemoryMb(),
                    s.getCpuMillicores(),
                    s.getRepoUrl(),
                    s.getBranch(),
                    s.getLastActiveAt(),
                    s.getCreatedAt()
            );
        }
    }

    public record ServiceMetricsResponse(
            Long serviceId,
            String serviceName,
            String slug,
            double cpuPercent,
            long memoryBytes,
            long memoryMb
    ) {
    }

    private final AppServiceRepository services;
    private final ProjectRepository projects;
    private final EntitlementService entitlements;
    private final ContainerRuntime runtime;
    private final DatabaseProvisionService dbService;
    private final String baseDomain;

    public AppServiceController(
            AppServiceRepository services,
            ProjectRepository projects,
            EntitlementService entitlements,
            ContainerRuntime runtime,
            DatabaseProvisionService dbService,
            @Value("${deployra.runtime.base-domain:localhost}") String baseDomain) {
        this.services = services;
        this.projects = projects;
        this.entitlements = entitlements;
        this.runtime = runtime;
        this.dbService = dbService;
        this.baseDomain = baseDomain;
    }

    /**
     * Liệt kê tất cả service trong 1 project.
     */
    @GetMapping("/api/projects/{projectId}/services")
    public List<ServiceResponse> listByProject(@PathVariable Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new NotFoundException("Không tìm thấy project với id: " + projectId);
        }
        return services.findByProjectIdAndDeletedAtIsNull(projectId).stream()
                .map(s -> ServiceResponse.from(s, baseDomain))
                .toList();
    }

    /**
     * Tạo service mới trong project (APP hoặc DATABASE).
     */
    @PostMapping("/api/projects/{projectId}/services")
    @Transactional
    public ResponseEntity<ServiceResponse> create(
            @PathVariable Long projectId,
            @RequestBody CreateServiceRequest req) {
        Project project = projects.findById(projectId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy project với id: " + projectId));

        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new InvalidRequestException("Tên service không được để trống");
        }

        AppService.Type type = req.type() != null ? req.type() : AppService.Type.APP;
        int memoryMb = req.memoryMb() != null ? req.memoryMb() : 256;
        int cpuMillicores = req.cpuMillicores() != null ? req.cpuMillicores() : 500;
        int port = req.port() != null ? req.port() : (type == AppService.Type.DATABASE ? 5432 : 8000);

        // Kiểm tra hạn mức tạo service
        Long ownerId = project.getOwner() != null ? project.getOwner().getId() : null;
        if (ownerId != null) {
            entitlements.requireCanCreateService(ownerId, type, memoryMb);
        }

        AppService service = new AppService();
        service.setProject(project);
        service.setName(req.name().trim());
        service.setType(type);
        service.setStatus(AppService.Status.IDLE);
        service.setPort(port);
        service.setMemoryMb(memoryMb);
        service.setCpuMillicores(cpuMillicores);
        if (req.rootDir() != null && !req.rootDir().isBlank()) {
            service.setRootDir(req.rootDir().trim());
        }
        if (req.dockerfilePath() != null && !req.dockerfilePath().isBlank()) {
            service.setDockerfilePath(req.dockerfilePath().trim());
        }
        if (req.repoUrl() != null && !req.repoUrl().isBlank()) {
            service.setRepoUrl(req.repoUrl().trim());
        }
        if (req.branch() != null && !req.branch().isBlank()) {
            service.setBranch(req.branch().trim());
        }

        AppService saved = services.save(service);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceResponse.from(saved, baseDomain));
    }

    /**
     * Xem thông tin chi tiết của 1 service.
     */
    @GetMapping("/api/services/{serviceId}")
    public ServiceResponse get(@PathVariable Long serviceId) {
        AppService s = findService(serviceId);
        return ServiceResponse.from(s, baseDomain);
    }

    /**
     * Cập nhật thông tin cấu hình của service.
     */
    @PutMapping("/api/services/{serviceId}")
    @Transactional
    public ServiceResponse update(@PathVariable Long serviceId, @RequestBody UpdateServiceRequest req) {
        AppService s = findService(serviceId);

        if (req.name() != null && !req.name().isBlank()) {
            s.setName(req.name().trim());
        }
        if (req.port() != null) {
            s.setPort(req.port());
        }
        if (req.branch() != null) {
            s.setBranch(req.branch().trim());
        }
        if (req.rootDir() != null) {
            s.setRootDir(req.rootDir().trim());
        }
        if (req.dockerfilePath() != null) {
            s.setDockerfilePath(req.dockerfilePath().trim());
        }
        if (req.memoryMb() != null) {
            s.setMemoryMb(req.memoryMb());
        }
        if (req.cpuMillicores() != null) {
            s.setCpuMillicores(req.cpuMillicores());
        }
        if (req.healthcheckPath() != null) {
            s.setHealthcheckPath(req.healthcheckPath().trim());
        }

        AppService saved = services.save(s);
        return ServiceResponse.from(saved, baseDomain);
    }

    /**
     * Xóa mềm service và dọn dẹp container Docker.
     */
    @DeleteMapping("/api/services/{serviceId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long serviceId) {
        AppService s = findService(serviceId);

        try {
            runtime.remove(s);
        } catch (Exception ignored) {
        }

        s.setDeletedAt(Instant.now());
        services.save(s);

        return ResponseEntity.noContent().build();
    }

    /**
     * Khởi động service container đang dừng.
     */
    @PostMapping("/api/services/{serviceId}/start")
    @Transactional
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        AppService s = findService(serviceId);

        Long ownerId = (userId != null) ? userId : (s.getProject().getOwner() != null ? s.getProject().getOwner().getId() : null);
        if (ownerId != null) {
            entitlements.requireCanStartService(ownerId, s);
        }

        if (s.getType() == AppService.Type.DATABASE) {
            dbService.start(ownerId, serviceId);
        } else {
            runtime.start(s);
            s.setStatus(AppService.Status.RUNNING);
            s.setLastActiveAt(Instant.now());
            services.save(s);
        }

        return ResponseEntity.ok(Map.of(
                "serviceId", s.getId(),
                "status", "RUNNING",
                "message", "Service đã khởi động thành công"
        ));
    }

    /**
     * Dừng service container để giải phóng RAM.
     */
    @PostMapping("/api/services/{serviceId}/stop")
    @Transactional
    public ResponseEntity<Map<String, Object>> stop(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        AppService s = findService(serviceId);
        Long ownerId = (userId != null) ? userId : (s.getProject().getOwner() != null ? s.getProject().getOwner().getId() : null);

        if (s.getType() == AppService.Type.DATABASE) {
            dbService.stop(ownerId, serviceId);
        } else {
            runtime.stop(s);
            s.setStatus(AppService.Status.STOPPED);
            services.save(s);
        }

        return ResponseEntity.ok(Map.of(
                "serviceId", s.getId(),
                "status", "STOPPED",
                "message", "Service đã dừng thành công"
        ));
    }

    /**
     * Khởi động lại service container (stop rồi start).
     */
    @PostMapping("/api/services/{serviceId}/restart")
    @Transactional
    public ResponseEntity<Map<String, Object>> restart(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        AppService s = findService(serviceId);
        Long ownerId = (userId != null) ? userId : (s.getProject().getOwner() != null ? s.getProject().getOwner().getId() : null);

        if (s.getType() == AppService.Type.DATABASE) {
            dbService.stop(ownerId, serviceId);
            dbService.start(ownerId, serviceId);
        } else {
            try {
                runtime.stop(s);
            } catch (Exception ignored) {
            }
            if (ownerId != null) {
                entitlements.requireCanStartService(ownerId, s);
            }
            runtime.start(s);
            s.setStatus(AppService.Status.RUNNING);
            s.setLastActiveAt(Instant.now());
            services.save(s);
        }

        return ResponseEntity.ok(Map.of(
                "serviceId", s.getId(),
                "status", "RUNNING",
                "message", "Service đã khởi động lại thành công"
        ));
    }

    /**
     * Lấy trực tiếp runtime log của container.
     */
    @GetMapping("/api/services/{serviceId}/logs")
    public ResponseEntity<Map<String, Object>> logs(
            @PathVariable Long serviceId,
            @RequestParam(defaultValue = "100") int tailLines) {
        AppService s = findService(serviceId);
        String logContent = runtime.logs(s, tailLines);

        return ResponseEntity.ok(Map.of(
                "serviceId", s.getId(),
                "serviceName", s.getName(),
                "tailLines", tailLines,
                "logs", logContent
        ));
    }

    /**
     * Lấy metrics thời gian thực (CPU %, RAM MB) của container.
     */
    @GetMapping("/api/services/{serviceId}/metrics")
    public ResponseEntity<ServiceMetricsResponse> metrics(@PathVariable Long serviceId) {
        AppService s = findService(serviceId);
        ContainerRuntime.ContainerStats stats = runtime.stats(s);

        long memoryMb = stats.memoryBytes() / (1024 * 1024);
        return ResponseEntity.ok(new ServiceMetricsResponse(
                s.getId(),
                s.getName(),
                s.getSlug(),
                stats.cpuPercent(),
                stats.memoryBytes(),
                memoryMb
        ));
    }

    private AppService findService(Long serviceId) {
        return services.findById(serviceId)
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service với id: " + serviceId));
    }
}
