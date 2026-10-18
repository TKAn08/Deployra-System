package com.paas.deployra.entitlement;

import com.paas.deployra.account.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Admin ghi đè hạn mức cho một user (thay thế giá trị của gói). {@code limitValue} = null là không giới hạn. */
@Entity
@Table(name = "user_entitlement_overrides",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "entitlement_key"}))
@Getter
@Setter
@NoArgsConstructor
public class UserEntitlementOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entitlement_key", nullable = false)
    private EntitlementKey entitlementKey;

    @Column(name = "limit_value")
    private Long limitValue;

    private String reason;

    /** null = vĩnh viễn. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
