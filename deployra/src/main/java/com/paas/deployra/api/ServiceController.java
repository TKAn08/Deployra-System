package com.paas.deployra.api;

import com.paas.deployra.api.dto.DeploymentDtos;
import com.paas.deployra.api.dto.ServiceDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceApiService services;

    public ServiceController(ServiceApiService services) {
        this.services = services;
    }

    @GetMapping("/{id}")
    public ServiceDtos.Detail get(@PathVariable Long id) {
        return services.get(id);
    }

    @GetMapping("/{id}/deployments")
    public List<DeploymentDtos.View> deployments(@PathVariable Long id) {
        return services.listDeployments(id);
    }

    @GetMapping("/{id}/deployments/latest")
    public DeploymentDtos.View latest(@PathVariable Long id) {
        return services.latestDeployment(id);
    }

    @PostMapping("/{id}/deploy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeploymentDtos.View deploy(@PathVariable Long id) {
        return services.enqueueDeploy(id);
    }
}
