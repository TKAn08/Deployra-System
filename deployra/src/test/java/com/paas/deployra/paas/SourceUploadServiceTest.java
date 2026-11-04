package com.paas.deployra.paas;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.deploy.DeploymentService;
import com.paas.deployra.deploy.EnvFileImporter;
import com.paas.deployra.deploy.GitSourceFetcher;
import com.paas.deployra.deploy.SourceFetchException;
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
import java.io.InputStream;
import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm tra luồng upload (chỉ lưu) và luồng deploy (xếp hàng) với kho thật trên
 * ổ
 * đĩa tạm; DB, hạn mức và hàng đợi deploy là mock.
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
    private EnvFileImporter envFileImporter;
    private GitSourceFetcher gitFetcher;
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
        envFileImporter = mock(EnvFileImporter.class);
        gitFetcher = mock(GitSourceFetcher.class);
        when(gitFetcher.validateRepoUrl(anyString()))
                .thenAnswer(inv -> URI.create(((String) inv.getArgument(0)).trim()));
        when(gitFetcher.requireBranch(any())).thenAnswer(inv -> {
            String b = inv.getArgument(0);
            return (b == null || b.isBlank()) ? null : b.trim();
        });

        service = new AppService();
        service.setId(SERVICE_ID);
        service.setName("demo");
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(SERVICE_ID, USER_ID))
                .thenReturn(Optional.of(service));
        when(users.getReferenceById(USER_ID)).thenReturn(new User());
        when(services.save(any(AppService.class))).thenAnswer(inv -> inv.getArgument(0));
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
        when(deployments.enqueue(any(AppService.class), any(Deployment.Trigger.class)))
                .thenAnswer(inv -> {
                    Deployment deployment = new Deployment();
                    deployment.setService(inv.getArgument(0));
                    deployment.setTrigger(inv.getArgument(1));
                    deployment.setSourceType(Deployment.SourceType.GIT);
                    return deployment;
                });

        uploadService = new SourceUploadService(services, uploads, users,
                new LocalSourceStorage(root.toString()), entitlements, deployments, envFileImporter,
                gitFetcher, Clock.fixed(NOW, ZoneOffset.UTC), 30);
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

    private SourceUpload upload(String filename, byte[] bytes) {
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
    void storesZipAndSavesRowWithoutStartingADeployment() throws Exception {
        byte[] bytes = zipBytes();

        SourceUpload saved = upload("my-app.ZIP", bytes);

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

        verify(entitlements).requireCanUpload(USER_ID, bytes.length);
        // upload và run là hai luồng riêng: upload xong không được tự xếp hàng deploy
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class),
                any(SourceUpload.class));
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class));
        // đọc tên biến trong .env của zip vừa lưu
        verify(envFileImporter).importFromZip(eq(service), any(InputStream.class), any());
    }

    @Test
    void envImportFailureDoesNotBreakAnUploadThatSucceeded() throws Exception {
        when(envFileImporter.importFromZip(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        SourceUpload saved = upload("a.zip", zipBytes());

        assertEquals(11L, saved.getId());
        assertEquals(1, storedFiles().size(), "file zip vẫn phải được giữ lại");
    }

    @Test
    void envImportIsSkippedWhenTheUploadIsRejected() throws Exception {
        assertThrows(InvalidRequestException.class, () -> upload("a.txt", zipBytes()));
        verify(envFileImporter, never()).importFromZip(any(), any(), any());
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
        verify(envFileImporter, never()).importFromZip(any(), any(), any());
    }

    // ---------- Luồng deploy tách riêng ----------

    private SourceUpload storedUpload(Instant expiresAt) {
        SourceUpload u = new SourceUpload();
        u.setId(11L);
        u.setExpiresAt(expiresAt);
        when(uploads.findByIdAndUserIdAndDeletedAtIsNull(11L, USER_ID)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void deployUploadQueuesAZipDeployment() {
        SourceUpload u = storedUpload(NOW.plusSeconds(3600));

        Deployment d = uploadService.deployUpload(USER_ID, SERVICE_ID, 11L);

        assertEquals(Deployment.SourceType.ZIP, d.getSourceType());
        assertEquals(Deployment.Trigger.MANUAL, d.getTrigger());
        assertEquals(u, d.getUpload());
    }

    @Test
    void deployUploadRejectsUploadThatIsMissingOrBelongsToSomeoneElse() {
        // không stub: repository trả Optional.empty() (upload của user khác, đã xoá,
        // hoặc không tồn tại)
        when(uploads.findByIdAndUserIdAndDeletedAtIsNull(11L, USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> uploadService.deployUpload(USER_ID, SERVICE_ID, 11L));
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class),
                any(SourceUpload.class));
    }

    @Test
    void deployUploadRejectsExpiredUpload() {
        storedUpload(NOW.minusSeconds(1));

        assertThrows(InvalidRequestException.class, () -> uploadService.deployUpload(USER_ID, SERVICE_ID, 11L));
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class),
                any(SourceUpload.class));
    }

    @Test
    void deployUploadRejectsServiceThatDoesNotBelongToTheUser() {
        assertThrows(NotFoundException.class, () -> uploadService.deployUpload(999L, SERVICE_ID, 11L));
    }

    @Test
    void deployFromGitQueuesAGitDeployment() {
        service.setRepoUrl("https://github.com/acme/demo.git");

        Deployment d = uploadService.deployFromGit(USER_ID, SERVICE_ID);

        assertEquals(Deployment.SourceType.GIT, d.getSourceType());
        assertEquals(Deployment.Trigger.MANUAL, d.getTrigger());
        assertNull(d.getUpload());
    }

    @Test
    void deployFromGitRequiresARepoUrl() {
        service.setRepoUrl(null);
        assertThrows(InvalidRequestException.class, () -> uploadService.deployFromGit(USER_ID, SERVICE_ID));
        service.setRepoUrl("  ");
        assertThrows(InvalidRequestException.class, () -> uploadService.deployFromGit(USER_ID, SERVICE_ID));
        verify(deployments, never()).enqueue(any(AppService.class), any(Deployment.Trigger.class));
    }

    @Test
    void deployFromGitRejectsDatabaseService() {
        service.setType(AppService.Type.DATABASE);
        service.setRepoUrl("https://github.com/acme/demo.git");
        assertThrows(InvalidRequestException.class, () -> uploadService.deployFromGit(USER_ID, SERVICE_ID));
    }

    // ---------- Lưu repo GitHub ----------

    private static final String REPO_URL = "https://github.com/acme/demo.git";

    @Test
    void setRepoSavesValidatedUrlAndBranch() {
        AppService saved = uploadService.setRepo(USER_ID, SERVICE_ID, "  " + REPO_URL + " ", " develop ");

        assertEquals(REPO_URL, saved.getRepoUrl());
        assertEquals("develop", saved.getBranch());
        verify(services).save(service);
    }

    @Test
    void blankBranchMeansRepoDefaultBranch() {
        service.setBranch("main");

        AppService saved = uploadService.setRepo(USER_ID, SERVICE_ID, REPO_URL, "  ");

        assertNull(saved.getBranch());
    }

    @Test
    void setRepoReplacesThePreviousRepo() {
        service.setRepoUrl("https://github.com/old/repo.git");
        service.setBranch("old");

        AppService saved = uploadService.setRepo(USER_ID, SERVICE_ID, REPO_URL, "main");

        assertEquals(REPO_URL, saved.getRepoUrl());
        assertEquals("main", saved.getBranch());
    }

    @Test
    void setRepoRejectsInvalidUrlAndSavesNothing() {
        when(gitFetcher.validateRepoUrl("http://evil.example/x.git"))
                .thenThrow(new SourceFetchException("Chỉ nhận repo URL dạng https://"));

        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> uploadService.setRepo(USER_ID, SERVICE_ID, "http://evil.example/x.git", "main"));

        assertEquals("Chỉ nhận repo URL dạng https://", e.getMessage());
        verify(services, never()).save(any(AppService.class));
    }

    @Test
    void setRepoRejectsInvalidBranchAndSavesNothing() {
        when(gitFetcher.requireBranch("bad..branch")).thenThrow(new SourceFetchException("Tên branch không hợp lệ"));

        assertThrows(InvalidRequestException.class,
                () -> uploadService.setRepo(USER_ID, SERVICE_ID, REPO_URL, "bad..branch"));
        verify(services, never()).save(any(AppService.class));
    }

    @Test
    void setRepoRejectsBlankUrl() {
        assertThrows(InvalidRequestException.class, () -> uploadService.setRepo(USER_ID, SERVICE_ID, "  ", "main"));
        assertThrows(InvalidRequestException.class, () -> uploadService.setRepo(USER_ID, SERVICE_ID, null, "main"));
        verify(services, never()).save(any(AppService.class));
    }

    @Test
    void setRepoRejectsServiceThatDoesNotBelongToTheUser() {
        assertThrows(NotFoundException.class, () -> uploadService.setRepo(999L, SERVICE_ID, REPO_URL, "main"));
        verify(services, never()).save(any(AppService.class));
    }

    @Test
    void setRepoRejectsDatabaseService() {
        service.setType(AppService.Type.DATABASE);

        assertThrows(InvalidRequestException.class, () -> uploadService.setRepo(USER_ID, SERVICE_ID, REPO_URL, "main"));
        verify(services, never()).save(any(AppService.class));
    }
}
