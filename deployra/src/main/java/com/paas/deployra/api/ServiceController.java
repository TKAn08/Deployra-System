package com.paas.deployra.api;

import com.paas.deployra.api.dto.DeploymentDtos;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceApiService services;

    public ServiceController(ServiceApiService services) {
        this.services = services;
    }

    // GET /{id} và GET /{id}/deployments do AppServiceController và DeploymentController
    // đảm nhận (vị trí số 1). Giữ ở đây sẽ gây "Ambiguous handler methods mapped".

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
