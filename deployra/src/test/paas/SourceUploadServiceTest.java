package com.paas.deployra.paas;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.deploy.DeploymentService;
import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.storage.LocalSourceStorage;
import com.paas.deployra.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm tra luồng upload với kho thật trên ổ đĩa tạm; DB, hạn mức và hàng đợi
 * deploy là mock.
 */
class SourceUploadServiceTest {

    private static final long USER_ID = 7L;
    private static final long SERVICE_ID = 5L;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private AppServiceRepository services;
    private SourceUploadRepository uploads;
    private UserRepository users;
    private EntitlementService entitlements;
    private DeploymentService deployments;
    private AppService service;
    private Path root;
    private SourceUploadService uploadService;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        root = tmp.resolve("store");
        services = mock(AppServiceRepository.class);
        uploads = mock(SourceUploadRepository.class);
        users = mock(UserRepository.class);
        entitlements = mock(EntitlementService.class);
        deployments = mock(DeploymentService.class);

        service = new AppService();
        service.setId(SERVICE_ID);
        service.setName("demo");
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(SERVICE_ID, USER_ID))
                .thenReturn(Optional.of(service));
        when(users.getReferenceById(USER_ID)).thenReturn(new User());
        when(uploads.save(any(SourceUpload.class))).thenAnswer(inv -> {
            SourceUpload saved = inv.getArgument(0);
            saved.setId(11L);
            return saved;
        });
        when(deployments.enqueue(any(AppService.class), any(Deployment.Trigger.class), any(SourceUpload.class)))
                .thenAnswer(inv -> {
                    Deployment deployment = new Deployment();
                    deployment.setService(inv.getArgument(0));
                    deployment.setTrigger(inv.getArgument(1));
                    deployment.setSourceType(Deployment.SourceType.ZIP);
                    deployment.setUpload(inv.getArgument(2));
                    return deployment;
                });

        uploadService = new SourceUploadService(services, uploads, users,
                new LocalSourceStorage(root.toString()), entitlements, deployments,
                Clock.fixed(NOW, ZoneOffset.UTC), 30);
    }

    private static byte[] zipBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("Dockerfile"));
            zip.write("FROM scratch\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private SourceUploadService.Result upload(String filename, byte[] bytes) {
        return uploadService.uploadZip(USER_ID, SERVICE_ID, filename, bytes.length, new ByteArrayInputStream(bytes));
    }

    private List<Path> storedFiles() throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void storesZipSavesRowAndQueuesZipDeployment() throws Exception {
        byte[] bytes = zipBytes();

        SourceUploadService.Result result = upload("my-app.ZIP", bytes);

        SourceUpload saved = result.upload();
        assertEquals(11L, saved.getId());
        assertEquals(SourceUpload.StorageBackend.LOCAL, saved.getStorageBackend());
        assertEquals(bytes.length, saved.getSizeBytes());
        assertEquals(NOW.plusSeconds(30L * 24 * 3600), saved.getExpiresAt());
        assertTrue(saved.getObjectKey().matches("uploads/7/[0-9a-f-]{36}\\.zip"), saved.getObjectKey());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), saved.getSha256());

        List<Path> files = storedFiles();
        assertEquals(1, files.size());
        assertEquals(root.resolve(saved.getObjectKey()), files.get(0));
        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(files.get(0))));

        Deployment deployment = result.deployment();
        assertEquals(Deployment.SourceType.ZIP, deployment.getSourceType());
        assertEquals(Deployment.Trigger.MANUAL, deployment.getTrigger());
        assertEquals(saved, deployment.getUpload());
        verify(entitlements).requireCanUpload(USER_ID, bytes.length);
    }

    @Test
    void rejectsServiceThatDoesNotBelongToTheUser() throws Exception {
        byte[] bytes = zipBytes();
        assertThrows(NotFoundException.class,
                () -> uploadService.uploadZip(999L, SERVICE_ID, "a.zip", bytes.length,
                        new ByteArrayInputStream(bytes)));
        verify(entitlements, never()).requireCanUpload(anyLong(), anyLong());
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void rejectsDatabaseService() throws Exception {
        service.setType(AppService.Type.DATABASE);
        assertThrows(InvalidRequestException.class, () -> upload("a.zip", zipBytes()));
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void rejectsWrongExtension() throws Exception {
        byte[] bytes = zipBytes();
        assertThrows(InvalidRequestException.class, () -> upload("a.tar.gz", bytes));
        assertThrows(InvalidRequestException.class, () -> upload("a", bytes));
        assertThrows(InvalidRequestException.class, () -> upload(null, bytes));
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void rejectsEmptyFile() {
        assertThrows(InvalidRequestException.class, () -> upload("a.zip", new byte[0]));
    }

    @Test
    void rejectsFileThatIsNotReallyAZip() throws Exception {
        byte[] fake = "đây là văn bản chứ không phải zip".getBytes(StandardCharsets.UTF_8);
        assertThrows(InvalidRequestException.class, () -> upload("a.zip", fake));
        assertThrows(InvalidRequestException.class, () -> upload("a.zip", new byte[] { 'P', 'K' }));
        assertEquals(List.of(), storedFiles());
        verify(uploads, never()).save(any(SourceUpload.class));
    }

    @Test
    void quotaExceededStopsBeforeAnythingIsStored() throws Exception {
        byte[] bytes = zipBytes();
        doThrow(new QuotaExceededException(EntitlementCodes.MAX_UPLOAD_MB, "File zip tối đa 50 MB"))
                .when(entitlements).requireCanUpload(USER_ID, bytes.length);

        assertThrows(QuotaExceededException.class, () -> upload("a.zip", bytes));

        assertEquals(List.of(), storedFiles());
        verify(uploads, never()).save(any(SourceUpload.class));
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class),
                any(SourceUpload.class));
    }

    @Test
    void declaredSizeThatDoesNotMatchLeavesNoFileAndNoRow() throws Exception {
        byte[] bytes = zipBytes();
        assertThrows(StorageException.class,
                () -> uploadService.uploadZip(USER_ID, SERVICE_ID, "a.zip", bytes.length + 10,
                        new ByteArrayInputStream(bytes)));
        assertEquals(List.of(), storedFiles());
        verify(uploads, never()).save(any(SourceUpload.class));
    }

    @Test
    void failingToSaveTheRowRemovesTheFile() throws Exception {
        when(uploads.save(any(SourceUpload.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> upload("a.zip", zipBytes()));

        assertEquals(List.of(), storedFiles());
    }

    @Test
    void failingToQueueRemovesTheFileAndMarksTheRowDeleted() throws Exception {
        when(deployments.enqueue(any(AppService.class), any(Deployment.Trigger.class), any(SourceUpload.class)))
                .thenThrow(new IllegalStateException("queue down"));

        assertThrows(IllegalStateException.class, () -> upload("a.zip", zipBytes()));

        assertEquals(List.of(), storedFiles());
        // lần lưu thứ hai là lần đánh dấu xoá mềm
        org.mockito.ArgumentCaptor<SourceUpload> captor = org.mockito.ArgumentCaptor.forClass(SourceUpload.class);
        verify(uploads, org.mockito.Mockito.times(2)).save(captor.capture());
        SourceUpload last = captor.getAllValues().get(1);
        assertNotNull(last.getDeletedAt());
        assertEquals(NOW, last.getDeletedAt());
    }
}