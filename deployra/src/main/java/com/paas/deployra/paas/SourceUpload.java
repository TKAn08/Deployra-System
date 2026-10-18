package com.paas.deployra.paas;

import com.paas.deployra.account.User;
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

import java.time.Instant;

/**
 * File zip mã nguồn người dùng upload. Chỉ lưu đường dẫn trong kho ({@code objectKey}),
 * không lưu thông tin truy cập S3: IAM Role / biến môi trường lo việc đó.
 */
@Entity
@Table(name = "source_uploads")
@Getter
@Setter
@NoArgsConstructor
public class SourceUpload {

    public enum StorageBackend { S3, R2, LOCAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_backend", nullable = false, length = 10)
    private StorageBackend storageBackend = StorageBackend.LOCAL;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
