package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.api.dto.ProjectDtos;
import com.paas.deployra.api.dto.ServiceDtos;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ProjectApiService {

    private final ProjectRepository projects;
    private final AppServiceRepository services;
    private final EntitlementService entitlements;
    private final CurrentUser currentUser;

    public ProjectApiService(ProjectRepository projects,
                             AppServiceRepository services,
                             EntitlementService entitlements,
                             CurrentUser currentUser) {
        this.projects = projects;
        this.services = services;
        this.entitlements = entitlements;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.Summary> list() {
        User owner = currentUser.require();
        return projects.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(owner.getId()).stream()
                .map(project -> new ProjectDtos.Summary(
                        project.getId(),
                        project.getName(),
                        project.getCreatedAt(),
                        services.findByProjectIdAndDeletedAtIsNull(project.getId()).size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDtos.Detail get(Long id) {
        Project project = requireOwned(id);
        List<ServiceDtos.Summary> serviceViews = services.findByProjectIdAndDeletedAtIsNull(project.getId())
                .stream()
                .map(this::toSummary)
                .toList();
        return new ProjectDtos.Detail(project.getId(), project.getName(), project.getCreatedAt(), serviceViews);
    }

    @Transactional
    public ProjectDtos.Summary create(ProjectDtos.CreateRequest request) {
        User owner = currentUser.require();
        entitlements.requireCanCreateProject(owner.getId());
        Project project = new Project();
        project.setOwner(owner);
        project.setName(request.name().trim());
        projects.save(project);
        return new ProjectDtos.Summary(project.getId(), project.getName(), project.getCreatedAt(), 0);
    }

    @Transactional
    public void delete(Long id) {
        Project project = requireOwned(id);
        project.setDeletedAt(Instant.now());
        for (AppService service : services.findByProjectIdAndDeletedAtIsNull(project.getId())) {
            service.setDeletedAt(Instant.now());
        }
    }

    Project requireOwned(Long id) {
        User owner = currentUser.require();
        return projects.findByIdAndOwnerIdAndDeletedAtIsNull(id, owner.getId())
                .orElseThrow(() -> new ApiNotFoundException("Không tìm thấy project"));
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
}
