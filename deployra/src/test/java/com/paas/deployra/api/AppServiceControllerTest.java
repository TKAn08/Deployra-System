package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.common.ApiExceptionHandler;
import com.paas.deployra.deploy.DatabaseProvisionService;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import com.paas.deployra.runtime.ContainerRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppServiceControllerTest {

    private AppServiceRepository services;
    private ProjectRepository projects;
    private EntitlementService entitlements;
    private ContainerRuntime runtime;
    private DatabaseProvisionService dbService;
    private MockMvc mvc;
    private Project project;
    private AppService appService;

    @BeforeEach
    void setUp() {
        services = mock(AppServiceRepository.class);
        projects = mock(ProjectRepository.class);
        entitlements = mock(EntitlementService.class);
        runtime = mock(ContainerRuntime.class);
        dbService = mock(DatabaseProvisionService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new AppServiceController(
                        services, projects, entitlements, runtime, dbService, "localhost"))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        User owner = new User();
        owner.setId(1L);

        project = new Project();
        project.setId(10L);
        project.setOwner(owner);

        appService = new AppService();
        appService.setId(50L);
        appService.setProject(project);
        appService.setName("web-app");
        appService.setSlug("web-app-abc123");
        appService.setType(AppService.Type.APP);
        appService.setStatus(AppService.Status.STOPPED);
        appService.setMemoryMb(256);
    }

    @Test
    void createAppServiceReturns201WithGeneratedSlugAndAccessUrl() throws Exception {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(services.save(any(AppService.class))).thenAnswer(inv -> {
            AppService s = inv.getArgument(0);
            s.setId(51L);
            s.setSlug("frontend-123456");
            return s;
        });

        mvc.perform(post("/api/projects/10/services")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"frontend\",\"type\":\"APP\",\"port\":80,\"memoryMb\":256}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.accessUrl").value("http://frontend-123456.localhost"))
                .andExpect(jsonPath("$.port").value(80));

        verify(entitlements).requireCanCreateService(1L, AppService.Type.APP, 256);
    }

    @Test
    void startAndStopAppServiceInvokeContainerRuntime() throws Exception {
        when(services.findById(50L)).thenReturn(Optional.of(appService));

        mvc.perform(post("/api/services/50/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(entitlements).requireCanStartService(1L, appService);
        verify(runtime).start(appService);

        mvc.perform(post("/api/services/50/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        verify(runtime).stop(appService);
    }

    @Test
    void metricsEndpointReturnsCpuAndMemoryMb() throws Exception {
        when(services.findById(50L)).thenReturn(Optional.of(appService));
        when(runtime.stats(appService))
                .thenReturn(new ContainerRuntime.ContainerStats(12.5, 64L * 1024 * 1024));

        mvc.perform(get("/api/services/50/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuPercent").value(12.5))
                .andExpect(jsonPath("$.memoryMb").value(64));
    }
}
