package com.paas.deployra.deploy;

import com.paas.deployra.account.User;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.*;
import com.paas.deployra.paas.Deployment.Status;
import com.paas.deployra.paas.Deployment.Trigger;
import com.paas.deployra.runtime.ContainerRuntime;
import com.paas.deployra.storage.SourceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceRollbackTest {

        @Mock
        private DeploymentRepository deployments;
        @Mock
        private AppServiceRepository services;
        @Mock
        private GitSourceFetcher gitFetcher;
        @Mock
        private ZipSourceExtractor zipExtractor;
        @Mock
        private SourceStorage storage;
        @Mock
        private ContainerRuntime runtime;
        @Mock
        private EnvVariableService envVars;
        @Mock
        private EnvFileImporter envFileImporter;
        @Mock
        private EntitlementService entitlements;
        @Mock
        private ProjectBuildPreparer buildPreparer;
        @Mock
        private ImagePruner imagePruner;

        private DeploymentService deploymentService;
        private AppService appService;
        private Deployment oldLive;
        private Deployment targetInactive;

        @BeforeEach
        void setUp() {
                TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class)) {
                        @Override
                        public <T> T execute(TransactionCallback<T> action) {
                                return action.doInTransaction(new SimpleTransactionStatus());
                        }
                };
                Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
                DeploymentProperties props = new DeploymentProperties(
                                1,
                                Duration.ofSeconds(60),
                                "/health",
                                8080,
                                Duration.ofSeconds(300));
                deploymentService = new DeploymentService(
                                deployments, services, gitFetcher, zipExtractor, storage,
                                runtime, envVars, envFileImporter, entitlements, buildPreparer,
                                imagePruner, clock, props, tx);

                User owner = new User();
                owner.setId(1L);

                Project project = new Project();
                project.setId(10L);
                project.setOwner(owner);

                appService = new AppService();
                appService.setId(100L);
                appService.setSlug("todo-api");
                appService.setType(AppService.Type.APP);
                appService.setStatus(AppService.Status.RUNNING);
                appService.setPort(8080);
                appService.setHealthcheckPath("/health");
                appService.setProject(project);

                targetInactive = new Deployment();
                targetInactive.setId(50L);
                targetInactive.setService(appService);
                targetInactive.setStatus(Status.INACTIVE);
                targetInactive.setSourceType(Deployment.SourceType.GIT);
                targetInactive.setCommitSha("abc1234567");
                targetInactive.setImage("paas/todo-api:abc1234-50");
                targetInactive.setImageBytes(123456L);
                targetInactive.setImagePruned(false);

                oldLive = new Deployment();
                oldLive.setId(60L);
                oldLive.setService(appService);
                oldLive.setStatus(Status.LIVE);
                oldLive.setImage("paas/todo-api:def5678-60");
        }

        @Test
        void rollbackEnqueuesAndDeploysOldImageWithoutRebuilding() {
                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));
                when(deployments.findById(50L)).thenReturn(Optional.of(targetInactive));
                when(deployments.findFirstByServiceIdAndStatusOrderByCreatedAtDesc(100L, Status.QUEUED))
                                .thenReturn(Optional.empty());
                when(deployments.save(any(Deployment.class))).thenAnswer(inv -> {
                        Deployment d = inv.getArgument(0);
                        if (d.getId() == null) {
                                d.setId(70L);
                        }
                        return d;
                });

                Deployment queued = deploymentService.rollbackById(1L, 100L, 50L);

                assertEquals(70L, queued.getId());
                assertEquals(Status.QUEUED, queued.getStatus());
                assertEquals(Trigger.ROLLBACK, queued.getTrigger());
                assertEquals("paas/todo-api:abc1234-50", queued.getImage());
                assertEquals(0, queued.getBuildSeconds());

                // Chạy pipeline đồng bộ cho deployment 70L
                when(deployments.findById(70L)).thenReturn(Optional.of(queued));
                when(deployments.findWithGraphById(70L)).thenReturn(Optional.of(queued));
                when(envVars.decryptedFor(100L)).thenReturn(Map.of("PORT", "8080"));
                when(runtime.startContainer(appService, "paas/todo-api:abc1234-50", Map.of("PORT", "8080")))
                                .thenReturn("container-rollback-1");
                when(runtime.waitHealthy(eq("container-rollback-1"), eq("/health"), eq(8080), any()))
                                .thenReturn(true);
                when(deployments.findFirstByServiceIdAndStatusOrderByCreatedAtDesc(100L, Status.LIVE))
                                .thenReturn(Optional.of(oldLive));
                when(imagePruner.pruneService(100L))
                                .thenReturn(new ImagePruner.PruneResult(1, java.util.List.of("paas/todo-api:old-10")));

                deploymentService.runOne(70L);

                // Không build lại, không fetch git/zip
                verify(runtime, never()).buildImage(any(), any(), any(), any(), any());
                verifyNoInteractions(gitFetcher, zipExtractor, buildPreparer);
                verify(entitlements, never()).requireCanBuild(anyLong());

                // Khởi chạy đúng image cũ & zero-downtime
                verify(runtime).startContainer(appService, "paas/todo-api:abc1234-50", Map.of("PORT", "8080"));
                verify(runtime).removeOtherContainers(appService, "container-rollback-1");
                verify(imagePruner).pruneService(100L);

                assertEquals(Status.LIVE, queued.getStatus());
                assertEquals(Status.INACTIVE, oldLive.getStatus());
                assertEquals(AppService.Status.RUNNING, appService.getStatus());
                assertTrue(queued.getBuildLog().contains("Rollback thành công"));
        }

        @Test
        void rollbackRejectsPrunedImage() {
                targetInactive.setImagePruned(true);
                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));
                when(deployments.findById(50L)).thenReturn(Optional.of(targetInactive));

                assertThrows(InvalidRequestException.class,
                                () -> deploymentService.rollbackById(1L, 100L, 50L));
        }

        @Test
        void rollbackRejectsAlreadyLiveDeployment() {
                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));
                when(deployments.findById(60L)).thenReturn(Optional.of(oldLive));

                assertThrows(InvalidRequestException.class,
                                () -> deploymentService.rollbackById(1L, 100L, 60L));
        }

        @Test
        void rollbackRejectsDeploymentWithoutImage() {
                targetInactive.setImage(null);
                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));
                when(deployments.findById(50L)).thenReturn(Optional.of(targetInactive));

                assertThrows(InvalidRequestException.class,
                                () -> deploymentService.rollbackById(1L, 100L, 50L));
        }

        @Test
        void rollbackRejectsDatabaseService() {
                appService.setType(AppService.Type.DATABASE);
                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));

                assertThrows(InvalidRequestException.class,
                                () -> deploymentService.rollbackById(1L, 100L, 50L));
        }

        @Test
        void rollbackThrowsNotFoundWhenDeploymentDoesNotBelongToService() {
                AppService other = new AppService();
                other.setId(999L);
                targetInactive.setService(other);

                when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(100L, 1L))
                                .thenReturn(Optional.of(appService));
                when(deployments.findById(50L)).thenReturn(Optional.of(targetInactive));

                assertThrows(NotFoundException.class,
                                () -> deploymentService.rollbackById(1L, 100L, 50L));
        }
}
