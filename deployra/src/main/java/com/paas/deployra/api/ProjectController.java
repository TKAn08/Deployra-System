package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Controller quản lý Project (CRUD) theo phân công Người 3 (API REST). Dùng
 * DTO, kiểm tra hạn mức qua EntitlementService, không trả Entity ra ngoài.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record CreateProjectRequest(String name, Long userId) {

    }

    public record UpdateProjectRequest(String name) {

    }

    public record ProjectResponse(Long id, String name, Long ownerId, Instant createdAt, int serviceCount) {

        public static ProjectResponse from(Project p, int serviceCount) {
            return new ProjectResponse(
                    p.getId(),
                    p.getName(),
                    p.getOwner() != null ? p.getOwner().getId() : null,
                    p.getCreatedAt(),
                    serviceCount
            );
        }
    }

    public record ServiceSummary(Long id, String name, String slug, String type, String status, Integer port, int memoryMb) {

        public static ServiceSummary from(AppService s) {
            return new ServiceSummary(
                    s.getId(),
                    s.getName(),
                    s.getSlug(),
                    s.getType().name(),
                    s.getStatus().name(),
                    s.getPort(),
                    s.getMemoryMb()
            );
        }
    }

    public record ProjectDetailResponse(Long id, String name, Long ownerId, Instant createdAt, List<ServiceSummary> services) {

        public static ProjectDetailResponse from(Project p, List<AppService> serviceList) {
            return new ProjectDetailResponse(
                    p.getId(),
                    p.getName(),
                    p.getOwner() != null ? p.getOwner().getId() : null,
                    p.getCreatedAt(),
                    serviceList.stream().map(ServiceSummary::from).toList()
            );
        }
    }

    private final ProjectRepository projects;
    private final AppServiceRepository services;
    private final UserRepository users;
    private final EntitlementService entitlements;

    public ProjectController(ProjectRepository projects,
            AppServiceRepository services,
            UserRepository users,
            EntitlementService entitlements) {
        this.projects = projects;
        this.services = services;
        this.users = users;
        this.entitlements = entitlements;
    }

    /**
     * Liệt kê danh sách project của người dùng.
     */
    @GetMapping
    public List<ProjectResponse> list(@RequestParam(required = false) Long userId) {
        List<Project> list = (userId != null)
                ? projects.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)
                : projects.findAll().stream()
                        .filter(p -> p.getDeletedAt() == null)
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .toList();

        return list.stream()
                .map(p -> {
                    int count = services.findByProjectIdAndDeletedAtIsNull(p.getId()).size();
                    return ProjectResponse.from(p, count);
                })
                .toList();
    }

    /**
     * Xem thông tin chi tiết của 1 project kèm danh sách services.
     */
    @GetMapping("/{projectId}")
    public ProjectDetailResponse get(@PathVariable Long projectId) {
        Project project = projects.findById(projectId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy project với id: " + projectId));

        List<AppService> serviceList = services.findByProjectIdAndDeletedAtIsNull(projectId);
        return ProjectDetailResponse.from(project, serviceList);
    }

    /**
     * Tạo mới project.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<ProjectResponse> create(@RequestBody CreateProjectRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new InvalidRequestException("Tên project không được để trống");
        }
        User owner = resolveUser(request.userId());
        entitlements.requireCanCreateProject(owner.getId());

        Project project = new Project();
        project.setName(request.name().trim());
        project.setOwner(owner);

        Project saved = projects.save(project);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(saved, 0));
    }

    /**
     * Cập nhật tên project.
     */
    @PutMapping("/{projectId}")
    @Transactional
    public ProjectResponse update(@PathVariable Long projectId, @RequestBody UpdateProjectRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new InvalidRequestException("Tên project không được để trống");
        }

        Project project = projects.findById(projectId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy project với id: " + projectId));

        project.setName(request.name().trim());
        Project saved = projects.save(project);

        int count = services.findByProjectIdAndDeletedAtIsNull(saved.getId()).size();
        return ProjectResponse.from(saved, count);
    }

    /**
     * Xóa mềm project (soft delete) và các service bên trong.
     */
    @DeleteMapping("/{projectId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long projectId) {
        Project project = projects.findById(projectId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy project với id: " + projectId));

        Instant now = Instant.now();
        project.setDeletedAt(now);
        projects.save(project);

        List<AppService> serviceList = services.findByProjectIdAndDeletedAtIsNull(projectId);
        for (AppService s : serviceList) {
            s.setDeletedAt(now);
            services.save(s);
        }

        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Long userId) {
        if (userId != null) {
            return users.findById(userId)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy người dùng: " + userId));
        }
        return users.findAll().stream().findFirst()
                .orElseThrow(() -> new InvalidRequestException("Hệ thống chưa có người dùng nào"));
    }
}
