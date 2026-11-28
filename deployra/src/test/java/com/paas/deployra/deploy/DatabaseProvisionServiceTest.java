package com.paas.deployra.deploy;

import com.paas.deployra.account.User;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.EnvVariableService;
import com.paas.deployra.paas.Project;
import com.paas.deployra.runtime.DatabaseRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseProvisionServiceTest {

    @Mock
    private AppServiceRepository services;

    @Mock
    private EnvVariableService envVars;

    @Mock
    private EntitlementService entitlements;

    @Mock
    private DatabaseRuntime databaseRuntime;

    private DatabaseProvisionService provisionService;

    private User owner;
    private Project project;
    private AppService dbService;

    @BeforeEach
    void setUp() {
        provisionService = new DatabaseProvisionService(
                services,
                envVars,
                entitlements,
                databaseRuntime,
                "postgres:16-alpine",
                Duration.ofSeconds(10)
        );

        owner = new User();
        owner.setId(1L);
        owner.setEmail("user@example.com");

        project = new Project();
        project.setId(10L);
        project.setName("Test Project");
        project.setOwner(owner);

        dbService = new AppService();
        dbService.setId(100L);
        dbService.setName("my-postgres");
        dbService.setSlug("my-postgres-abc123");
        dbService.setType(AppService.Type.DATABASE);
        dbService.setStatus(AppService.Status.IDLE);
        dbService.setProject(project);
        dbService.setMemoryMb(256);
        dbService.setCpuMillicores(500);
    }

    @Test
    void provisionSuccessfulGeneratesCredentialsAndStartsContainer() {
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                .thenReturn(Optional.of(dbService));
        when(envVars.decryptedFor(100L)).thenReturn(new HashMap<>());
        when(databaseRuntime.startPostgres(eq(dbService), any())).thenReturn("container-12345");
        when(databaseRuntime.waitHealthy(eq("container-12345"), anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(databaseRuntime.getVolumeSizeBytes(dbService)).thenReturn(1048576L);
        when(services.save(any(AppService.class))).thenAnswer(i -> i.getArgument(0));

        DatabaseProvisionService.DatabaseConnectionInfo info = provisionService.provision(1L, 100L);

        assertNotNull(info);
        assertEquals(AppService.Status.RUNNING, info.status());
        assertEquals(5432, info.port());
        assertEquals("postgres", info.username());
        assertFalse(info.password().isBlank());
        assertTrue(info.databaseUrl().startsWith("postgresql://"));
        assertTrue(info.databaseUrl().contains("deployra-db-my-postgres-abc123:5432"));

        verify(entitlements).requireCanStartService(eq(1L), eq(dbService));
        verify(envVars).set(eq(dbService), eq("POSTGRES_DB"), anyString());
        verify(envVars).set(eq(dbService), eq("POSTGRES_USER"), eq("postgres"));
        verify(envVars).set(eq(dbService), eq("POSTGRES_PASSWORD"), anyString());
        verify(envVars).set(eq(dbService), eq("DATABASE_URL"), anyString());
        verify(databaseRuntime).startPostgres(eq(dbService), any());
    }

    @Test
    void provisionRejectsNonDatabaseService() {
        AppService app = new AppService();
        app.setId(101L);
        app.setType(AppService.Type.APP);
        app.setProject(project);

        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(101L, 1L))
                .thenReturn(Optional.of(app));

        assertThrows(InvalidRequestException.class, () -> provisionService.provision(1L, 101L));
    }

    @Test
    void stopDatabaseUpdatesStatus() {
        dbService.setStatus(AppService.Status.RUNNING);
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                .thenReturn(Optional.of(dbService));

        provisionService.stop(1L, 100L);

        verify(databaseRuntime).stop(dbService);
        assertEquals(AppService.Status.STOPPED, dbService.getStatus());
        verify(services).save(dbService);
    }

    @Test
    void linkToAppInjectsDatabaseUrlIntoTargetAppService() {
        AppService targetApp = new AppService();
        targetApp.setId(200L);
        targetApp.setName("web-api");
        targetApp.setType(AppService.Type.APP);
        targetApp.setProject(project); // Cùng project

        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                .thenReturn(Optional.of(dbService));
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(200L, 1L))
                .thenReturn(Optional.of(targetApp));

        String testDbUrl = "postgresql://postgres:secret123@deployra-db-slug:5432/mydb";
        when(envVars.decryptedFor(100L)).thenReturn(Map.of("DATABASE_URL", testDbUrl));

        boolean result = provisionService.linkToApp(1L, 100L, 200L);

        assertTrue(result);
        verify(envVars).set(targetApp, "DATABASE_URL", testDbUrl);
    }

    @Test
    void linkToAppFailsIfInDifferentProject() {
        Project otherProject = new Project();
        otherProject.setId(99L);
        otherProject.setOwner(owner);

        AppService targetApp = new AppService();
        targetApp.setId(200L);
        targetApp.setType(AppService.Type.APP);
        targetApp.setProject(otherProject); // Khác project

        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                .thenReturn(Optional.of(dbService));
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(200L, 1L))
                .thenReturn(Optional.of(targetApp));

        assertThrows(InvalidRequestException.class, () -> provisionService.linkToApp(1L, 100L, 200L));
    }
}
