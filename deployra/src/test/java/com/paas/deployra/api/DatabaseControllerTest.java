package com.paas.deployra.api;

import com.paas.deployra.common.ApiExceptionHandler;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.deploy.DatabaseProvisionService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.runtime.DatabaseRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatabaseControllerTest {

    private DatabaseProvisionService dbService;
    private DatabaseRuntime runtime;
    private AppServiceRepository services;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        dbService = mock(DatabaseProvisionService.class);
        runtime = mock(DatabaseRuntime.class);
        services = mock(AppServiceRepository.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new DatabaseController(dbService, runtime, services))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DatabaseProvisionService.DatabaseConnectionInfo sampleInfo() {
        return new DatabaseProvisionService.DatabaseConnectionInfo(
                10L,
                "my-postgres",
                "my-postgres-abc",
                "deployra-db-my-postgres-abc",
                5432,
                "db_my_postgres",
                "postgres",
                "secretPassword123",
                "postgresql://postgres:secretPassword123@deployra-db-my-postgres-abc:5432/db_my_postgres",
                AppService.Status.RUNNING,
                1024L
        );
    }

    @Test
    void provisionReturns200AndConnectionInfo() throws Exception {
        when(dbService.provision(isNull(), eq(10L))).thenReturn(sampleInfo());

        mvc.perform(post("/api/services/10/database/provision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(10))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.port").value(5432))
                .andExpect(jsonPath("$.host").value("deployra-db-my-postgres-abc"))
                .andExpect(jsonPath("$.databaseUrl").value("postgresql://postgres:secretPassword123@deployra-db-my-postgres-abc:5432/db_my_postgres"));

        verify(dbService).provision(isNull(), eq(10L));
    }

    @Test
    void stopReturns200() throws Exception {
        mvc.perform(post("/api/services/10/database/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        verify(dbService).stop(isNull(), eq(10L));
    }

    @Test
    void getInfoReturns200AndConnectionDetails() throws Exception {
        when(dbService.getConnectionInfo(isNull(), eq(10L))).thenReturn(sampleInfo());

        mvc.perform(get("/api/services/10/database/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("postgres"))
                .andExpect(jsonPath("$.database").value("db_my_postgres"));

        verify(dbService).getConnectionInfo(isNull(), eq(10L));
    }

    @Test
    void linkToAppSuccessReturns200() throws Exception {
        when(dbService.linkToApp(isNull(), eq(10L), eq(20L))).thenReturn(true);

        mvc.perform(post("/api/services/10/database/link/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.databaseServiceId").value(10))
                .andExpect(jsonPath("$.targetAppServiceId").value(20));

        verify(dbService).linkToApp(isNull(), eq(10L), eq(20L));
    }

    @Test
    void linkToAppThrows400WhenDifferentProject() throws Exception {
        when(dbService.linkToApp(isNull(), eq(10L), eq(99L)))
                .thenThrow(new InvalidRequestException("Database và App phải thuộc cùng một project"));

        mvc.perform(post("/api/services/10/database/link/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void provisionNotFoundReturns404() throws Exception {
        when(dbService.provision(isNull(), eq(999L)))
                .thenThrow(new NotFoundException("Không tìm thấy service"));

        mvc.perform(post("/api/services/999/database/provision"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
