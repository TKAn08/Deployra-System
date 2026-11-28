package com.paas.deployra.deploy;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.EnvVariableService;
import com.paas.deployra.runtime.DatabaseRuntime;
import com.paas.deployra.runtime.DockerDatabaseRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Service quản lý vòng đời và cấp phát (provisioning) cho Database Service (PostgreSQL).
 */
@Service
public class DatabaseProvisionService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProvisionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public record DatabaseConnectionInfo(
            Long serviceId,
            String serviceName,
            String slug,
            String host,
            int port,
            String database,
            String username,
            String password,
            String databaseUrl,
            AppService.Status status,
            long volumeBytes
    ) {
    }

    private final AppServiceRepository services;
    private final EnvVariableService envVars;
    private final EntitlementService entitlements;
    private final DatabaseRuntime databaseRuntime;
    private final String defaultPostgresImage;
    private final Duration healthTimeout;

    public DatabaseProvisionService(
            AppServiceRepository services,
            EnvVariableService envVars,
            EntitlementService entitlements,
            DatabaseRuntime databaseRuntime,
            @Value("${deployra.runtime.postgres-image:postgres:16-alpine}") String defaultPostgresImage,
            @Value("${deployra.runtime.db-health-timeout:30s}") Duration healthTimeout) {
        this.services = services;
        this.envVars = envVars;
        this.entitlements = entitlements;
        this.databaseRuntime = databaseRuntime;
        this.defaultPostgresImage = defaultPostgresImage;
        this.healthTimeout = healthTimeout;
    }

    /**
     * Cấp phát hoặc triển khai một database PostgreSQL (tạo volume, chạy container, chờ healthy).
     */
    @Transactional
    public DatabaseConnectionInfo provision(Long userId, Long serviceId) {
        AppService service = requireOwnedDbService(userId, serviceId);

        // 1. Kiểm tra hạn mức RAM
        Long ownerId = service.getProject().getOwner().getId();
        entitlements.requireCanStartService(ownerId, service);

        // 2. Đảm bảo cổng mặc định 5432
        if (service.getPort() == null || service.getPort() == 8000) {
            service.setPort(5432);
        }

        // 3. Chuẩn bị hoặc tự sinh credentials
        Map<String, String> existingVars = envVars.decryptedFor(serviceId);
        String dbName = existingVars.computeIfAbsent("POSTGRES_DB", k -> {
            String name = "db_" + service.getSlug().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            envVars.set(service, "POSTGRES_DB", name);
            return name;
        });

        String username = existingVars.computeIfAbsent("POSTGRES_USER", k -> {
            envVars.set(service, "POSTGRES_USER", "postgres");
            return "postgres";
        });

        String password = existingVars.computeIfAbsent("POSTGRES_PASSWORD", k -> {
            String generated = generateRandomPassword(20);
            envVars.set(service, "POSTGRES_PASSWORD", generated);
            return generated;
        });

        // 4. Sinh URL kết nối nội bộ (hostname là containerName trong Docker network)
        String host = DockerDatabaseRuntime.containerName(service.getSlug());
        int port = service.getPort();
        String databaseUrl = String.format("postgresql://%s:%s@%s:%d/%s", username, password, host, port, dbName);
        envVars.set(service, "DATABASE_URL", databaseUrl);

        // 5. Khởi chạy container
        DatabaseRuntime.DatabaseConfig config = new DatabaseRuntime.DatabaseConfig(
                defaultPostgresImage,
                dbName,
                username,
                password,
                port,
                service.getMemoryMb(),
                service.getCpuMillicores(),
                existingVars
        );

        String containerId = databaseRuntime.startPostgres(service, config);

        // 6. Chờ database sẵn sàng
        boolean healthy = databaseRuntime.waitHealthy(containerId, username, dbName, port, healthTimeout);
        if (!healthy) {
            service.setStatus(AppService.Status.FAILED);
            services.save(service);
            throw new IllegalStateException("PostgreSQL không thể khởi động đúng hạn (" + healthTimeout.toSeconds() + "s)");
        }

        // 7. Cập nhật trạng thái RUNNING
        service.setStatus(AppService.Status.RUNNING);
        service.setVolumeBytes(databaseRuntime.getVolumeSizeBytes(service));
        AppService saved = services.save(service);

        log.info("Khởi chạy Database Service thành công: {} (id={})", saved.getName(), saved.getId());

        return new DatabaseConnectionInfo(
                saved.getId(),
                saved.getName(),
                saved.getSlug(),
                host,
                port,
                dbName,
                username,
                password,
                databaseUrl,
                saved.getStatus(),
                saved.getVolumeBytes()
        );
    }

    /**
     * Dừng database container.
     */
    @Transactional
    public void stop(Long userId, Long serviceId) {
        AppService service = requireOwnedDbService(userId, serviceId);
        databaseRuntime.stop(service);
        service.setStatus(AppService.Status.STOPPED);
        services.save(service);
        log.info("Đã dừng Database Service: {} (id={})", service.getName(), service.getId());
    }

    /**
     * Khởi động lại database container đã dừng.
     */
    @Transactional
    public DatabaseConnectionInfo start(Long userId, Long serviceId) {
        AppService service = requireOwnedDbService(userId, serviceId);
        Long ownerId = service.getProject().getOwner().getId();
        entitlements.requireCanStartService(ownerId, service);

        databaseRuntime.start(service);

        Map<String, String> vars = envVars.decryptedFor(serviceId);
        String username = vars.getOrDefault("POSTGRES_USER", "postgres");
        String dbName = vars.getOrDefault("POSTGRES_DB", "postgres");
        int port = service.getPort() != null ? service.getPort() : 5432;

        boolean healthy = databaseRuntime.waitHealthy(
                DockerDatabaseRuntime.containerName(service.getSlug()),
                username, dbName, port, healthTimeout);

        if (!healthy) {
            service.setStatus(AppService.Status.FAILED);
            services.save(service);
            throw new IllegalStateException("Database không khởi động lại được");
        }

        service.setStatus(AppService.Status.RUNNING);
        AppService saved = services.save(service);
        return getConnectionInfo(userId, serviceId);
    }

    /**
     * Lấy thông tin kết nối và mật khẩu đã giải mã của database.
     */
    @Transactional(readOnly = true)
    public DatabaseConnectionInfo getConnectionInfo(Long userId, Long serviceId) {
        AppService service = requireOwnedDbService(userId, serviceId);
        Map<String, String> vars = envVars.decryptedFor(serviceId);

        String host = DockerDatabaseRuntime.containerName(service.getSlug());
        int port = service.getPort() != null ? service.getPort() : 5432;
        String dbName = vars.getOrDefault("POSTGRES_DB", "");
        String username = vars.getOrDefault("POSTGRES_USER", "");
        String password = vars.getOrDefault("POSTGRES_PASSWORD", "");
        String databaseUrl = vars.getOrDefault("DATABASE_URL",
                String.format("postgresql://%s:%s@%s:%d/%s", username, password, host, port, dbName));

        return new DatabaseConnectionInfo(
                service.getId(),
                service.getName(),
                service.getSlug(),
                host,
                port,
                dbName,
                username,
                password,
                databaseUrl,
                service.getStatus(),
                service.getVolumeBytes()
        );
    }

    /**
     * Liên kết Database này với một APP service cùng project:
     * Tự động truyền biến DATABASE_URL vào biến môi trường của APP service đó.
     */
    @Transactional
    public boolean linkToApp(Long userId, Long dbServiceId, Long targetAppServiceId) {
        AppService db = requireOwnedDbService(userId, dbServiceId);
        AppService app = requireOwnedAppService(userId, targetAppServiceId);

        if (!db.getProject().getId().equals(app.getProject().getId())) {
            throw new InvalidRequestException("Database và App phải thuộc cùng một project");
        }

        Map<String, String> dbVars = envVars.decryptedFor(dbServiceId);
        String dbUrl = dbVars.get("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new InvalidRequestException("Database chưa có thông tin DATABASE_URL (cần provision trước)");
        }

        envVars.set(app, "DATABASE_URL", dbUrl);
        log.info("Đã liên kết Database {} với App {}", db.getName(), app.getName());
        return true;
    }

    // ---------- Kiểm tra quyền sở hữu ----------

    private AppService requireOwnedDbService(Long userId, Long serviceId) {
        AppService service = (userId == null)
                ? services.findById(serviceId).filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"))
                : services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));

        if (service.getType() != AppService.Type.DATABASE) {
            throw new InvalidRequestException("Service " + serviceId + " không phải loại DATABASE");
        }
        return service;
    }

    private AppService requireOwnedAppService(Long userId, Long serviceId) {
        AppService service = (userId == null)
                ? services.findById(serviceId).filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"))
                : services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));

        if (service.getType() != AppService.Type.APP) {
            throw new InvalidRequestException("Service " + serviceId + " không phải loại APP");
        }
        return service;
    }

    private static String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
