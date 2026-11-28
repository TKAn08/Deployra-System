package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.ApiExceptionHandler;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectControllerTest {

    private ProjectRepository projects;
    private AppServiceRepository services;
    private UserRepository users;
    private EntitlementService entitlements;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        services = mock(AppServiceRepository.class);
        users = mock(UserRepository.class);
        entitlements = mock(EntitlementService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ProjectController(projects, services, users, entitlements))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createProjectReturns201WhenWithinQuota() throws Exception {
        User user = new User();
        user.setId(3L);
        when(users.findById(3L)).thenReturn(Optional.of(user));
        when(projects.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(10L);
            p.setCreatedAt(Instant.now());
            return p;
        });

        mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shop App\",\"userId\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Shop App"))
                .andExpect(jsonPath("$.ownerId").value(3));

        verify(entitlements).requireCanCreateProject(3L);
    }

    @Test
    void createProjectOverQuotaReturns403() throws Exception {
        User user = new User();
        user.setId(3L);
        when(users.findById(3L)).thenReturn(Optional.of(user));
        doThrow(new QuotaExceededException(EntitlementCodes.MAX_PROJECTS, "Đã đạt số project tối đa"))
                .when(entitlements).requireCanCreateProject(3L);

        mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Second App\",\"userId\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("quota_exceeded"));
    }

    @Test
    void createProjectWithBlankNameReturns400() throws Exception {
        mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void deleteProjectSoftDeletesProjectAndServices() throws Exception {
        Project project = new Project();
        project.setId(10L);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(services.findByProjectIdAndDeletedAtIsNull(10L)).thenReturn(List.of());

        mvc.perform(delete("/api/projects/10"))
                .andExpect(status().isNoContent());

        verify(projects).save(argThat(p -> p.getDeletedAt() != null));
    }
}
