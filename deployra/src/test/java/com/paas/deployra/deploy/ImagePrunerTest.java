package com.paas.deployra.deploy;

import com.paas.deployra.account.User;
import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.Limit;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.Deployment.Status;
import com.paas.deployra.paas.DeploymentRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.runtime.ContainerRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImagePrunerTest {

    @Mock
    private DeploymentRepository deployments;

    @Mock
    private AppServiceRepository services;

    @Mock
    private EntitlementService entitlements;

    @Mock
    private ContainerRuntime runtime;

    private ImagePruner pruner;
    private AppService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class)) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        pruner = new ImagePruner(deployments, services, entitlements, runtime, tx);

        User owner = new User();
        owner.setId(1L);

        Project project = new Project();
        project.setId(10L);
        project.setOwner(owner);

        service = new AppService();
        service.setId(100L);
        service.setSlug("web-app-123");
        service.setProject(project);
    }

    private Deployment dep(long id, Status status, String image) {
        Deployment d = new Deployment();
        d.setId(id);
        d.setService(service);
        d.setStatus(status);
        d.setImage(image);
        d.setImagePruned(false);
        d.setCreatedAt(Instant.now().plusSeconds(id));
        return d;
    }

    @Test
    void prunesOldImagesBeyondRetainedLimitAndMarksImagePrunedTrue() {
        Deployment d4 = dep(4L, Status.LIVE, "paas/web-app-123:4");
        Deployment d3 = dep(3L, Status.INACTIVE, "paas/web-app-123:3");
        Deployment d2 = dep(2L, Status.INACTIVE, "paas/web-app-123:2");
        Deployment d1 = dep(1L, Status.INACTIVE, "paas/web-app-123:1");

        when(services.findById(100L)).thenReturn(Optional.of(service));
        when(entitlements.effectiveLimit(1L, EntitlementCodes.DEPLOYMENTS_RETAINED))
                .thenReturn(Limit.of(2));
        when(deployments.findByServiceIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(d4, d3, d2, d1));
        when(deployments.findById(2L)).thenReturn(Optional.of(d2));
        when(deployments.findById(1L)).thenReturn(Optional.of(d1));

        ImagePruner.PruneResult result = pruner.pruneService(100L);

        assertEquals(2, result.prunedCount());
        assertEquals(List.of("paas/web-app-123:2", "paas/web-app-123:1"), result.removedImages());
        verify(runtime).removeImage("paas/web-app-123:2");
        verify(runtime).removeImage("paas/web-app-123:1");
        verify(runtime, never()).removeImage("paas/web-app-123:4");
        verify(runtime, never()).removeImage("paas/web-app-123:3");

        assertFalse(d4.isImagePruned());
        assertFalse(d3.isImagePruned());
        assertTrue(d2.isImagePruned());
        assertTrue(d1.isImagePruned());
    }

    @Test
    void doesNotDeleteDockerImageIfSharedWithRetainedRollbackDeployment() {
        // d3 là rollback dùng lại image của d1
        Deployment d3 = dep(3L, Status.LIVE, "paas/web-app-123:1");
        Deployment d2 = dep(2L, Status.INACTIVE, "paas/web-app-123:2");
        Deployment d1 = dep(1L, Status.INACTIVE, "paas/web-app-123:1");

        when(services.findById(100L)).thenReturn(Optional.of(service));
        when(entitlements.effectiveLimit(1L, EntitlementCodes.DEPLOYMENTS_RETAINED))
                .thenReturn(Limit.of(2));
        when(deployments.findByServiceIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(d3, d2, d1));
        when(deployments.findById(1L)).thenReturn(Optional.of(d1));

        ImagePruner.PruneResult result = pruner.pruneService(100L);

        assertEquals(1, result.prunedCount());
        assertTrue(result.removedImages().isEmpty());
        verify(runtime, never()).removeImage("paas/web-app-123:1");
        assertTrue(d1.isImagePruned());
        assertFalse(d3.isImagePruned());
    }

    @Test
    void unlimitedRetentionDoesNotPruneAnything() {
        when(services.findById(100L)).thenReturn(Optional.of(service));
        when(entitlements.effectiveLimit(1L, EntitlementCodes.DEPLOYMENTS_RETAINED))
                .thenReturn(Limit.UNLIMITED);

        ImagePruner.PruneResult result = pruner.pruneService(100L);

        assertEquals(0, result.prunedCount());
        verifyNoInteractions(runtime);
    }

    @Test
    void keepsImagePrunedFalseWhenDockerRemoveFails() {
        Deployment d2 = dep(2L, Status.LIVE, "paas/web-app-123:2");
        Deployment d1 = dep(1L, Status.INACTIVE, "paas/web-app-123:1");

        when(services.findById(100L)).thenReturn(Optional.of(service));
        when(entitlements.effectiveLimit(1L, EntitlementCodes.DEPLOYMENTS_RETAINED))
                .thenReturn(Limit.of(1));
        when(deployments.findByServiceIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(d2, d1));
        doThrow(new IllegalStateException("docker daemon error"))
                .when(runtime).removeImage("paas/web-app-123:1");

        ImagePruner.PruneResult result = pruner.pruneService(100L);

        assertEquals(0, result.prunedCount());
        assertFalse(d1.isImagePruned());
    }
}
