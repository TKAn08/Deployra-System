package com.paas.deployra.api;

import com.paas.deployra.api.dto.ProjectDtos;
import com.paas.deployra.api.dto.ServiceDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectApiService projects;
    private final ServiceApiService serviceApi;

    public ProjectController(ProjectApiService projects, ServiceApiService serviceApi) {
        this.projects = projects;
        this.serviceApi = serviceApi;
    }

    @GetMapping
    public List<ProjectDtos.Summary> list() {
        return projects.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDtos.Summary create(@Valid @RequestBody ProjectDtos.CreateRequest request) {
        return projects.create(request);
    }

    @GetMapping("/{id}")
    public ProjectDtos.Detail get(@PathVariable Long id) {
        return projects.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        projects.delete(id);
    }

    @PostMapping("/{id}/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDtos.Summary createService(@PathVariable Long id,
                                             @Valid @RequestBody ServiceDtos.CreateRequest request) {
        return serviceApi.create(id, request);
    }
}
