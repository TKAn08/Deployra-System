package com.paas.deployra.paas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Một lần deploy: clone/giải nén zip → build image → chạy → healthcheck. */
@Entity
@Table(name = "deployments", indexes = @Index(name = "idx_deployments_service_created", columnList = "service_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class Deployment {
    public enum Status {
        QUEUED, BUILDING, DEPLOYING, LIVE, INACTIVE, FAILED, CANCELLED;

        public boolean canTransitionTo(Status next) {
            if (next == null || next == this)
                return false;
            return switch (this) {
                case QUEUED -> next == BUILDING || next == CANCELLED;
                case BUILDING -> next == DEPLOYING || next == FAILED || next == CANCELLED;
                case DEPLOYING -> next == LIVE || next == FAILED || next == CANCELLED;
                case LIVE -> next == INACTIVE; // chỉ khi có deploy mới LIVE
                case INACTIVE, FAILED, CANCELLED -> false; // trạng thái cuối
            };
        }

        /** Deploy đã kết thúc (thành công/thất bại/huỷ) — dùng để set finishedAt. */
        public boolean endsDeploy() {
            return this == LIVE || this == FAILED || this == CANCELLED;
        }
    }

    public enum Trigger {
        MANUAL, WEBHOOK, REDEPLOY, ROLLBACK
    }

    public enum SourceType {
        GIT, ZIP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private AppService service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private Trigger trigger = Trigger.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private SourceType sourceType = SourceType.GIT;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    /** Có khi sourceType = ZIP. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_id")
    private SourceUpload upload;

    /**
     * Chuỗi image đầy đủ (vd paas/slug:abc1234-12, sau này có thể là địa chỉ ECR).
     */
    @Column(length = 500)
    private String image;

    @Column(name = "image_bytes")
    private Long imageBytes;

    /**
     * true khi image đã bị dọn khỏi máy chủ (không còn tính vào dung lượng, không
     * rollback được).
     */
    @Column(name = "image_pruned", nullable = false)
    private boolean imagePruned = false;

    @Column(name = "build_seconds")
    private Integer buildSeconds;

    @Column(name = "build_log", columnDefinition = "text")
    private String buildLog;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void transitionTo(Status next, Instant now) {
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Chuyển trạng thái sai: " + this.status + " -> " + next);
        }
        this.status = next;
        if (next == Status.BUILDING) {
            this.startedAt = now;
        }
        if (next.endsDeploy()) {
            this.finishedAt = now;
        }
    }
}
