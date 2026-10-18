package com.paas.deployra.paas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * Một service (app hoặc database) trong project. Bảng tên {@code services}; lớp đặt tên AppService
 * để khỏi nhầm với annotation {@code @Service} của Spring.
 */
@Entity
@Table(name = "services")
@Getter
@Setter
@NoArgsConstructor
public class AppService {

    public enum Type { APP, DATABASE }

    public enum Status { IDLE, RUNNING, SLEEPING, STOPPED, FAILED, SUSPENDED }

    public enum SuspendedReason { BANDWIDTH_EXCEEDED, MEMORY_OVER_QUOTA, PLAN_EXPIRED, ABUSE }

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    /** Dùng làm subdomain; duy nhất và không bao giờ tái sử dụng (kể cả sau khi xoá mềm). */
    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private Type type = Type.APP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.IDLE;

    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    @Column(length = 255)
    private String branch = "main";

    @Column(name = "root_dir", length = 255)
    private String rootDir = ".";

    @Column(name = "dockerfile_path", length = 255)
    private String dockerfilePath = "Dockerfile";

    private Integer port = 8000;

    @Column(name = "healthcheck_path", length = 255)
    private String healthcheckPath = "/";

    @Column(name = "memory_mb", nullable = false)
    private int memoryMb = 256;

    @Column(name = "cpu_millicores", nullable = false)
    private int cpuMillicores = 500;

    @Column(name = "volume_bytes", nullable = false)
    private long volumeBytes = 0;

    /** Node chạy service. Null khi chỉ có 1 máy. Chưa có FK vì bảng nodes chỉ tạo khi có node thứ 2. */
    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspended_reason", length = 30)
    private SuspendedReason suspendedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (slug == null || slug.isBlank()) {
            slug = generateSlug(name);
        }
    }

    /** Service đang chiếm RAM trong pool của user. Service dừng/idle không chiếm. */
    public boolean reservesMemory() {
        return status == Status.RUNNING || status == Status.SLEEPING;
    }

    public static String generateSlug(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = "app";
        }
        StringBuilder sb = new StringBuilder(base).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
