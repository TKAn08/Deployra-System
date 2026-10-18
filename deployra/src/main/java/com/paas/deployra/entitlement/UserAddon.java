package com.paas.deployra.entitlement;

import com.paas.deployra.account.Payment;
import com.paas.deployra.account.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Một lần mua add-on. Mua nhiều lần thì cộng dồn. {@code delta} và {@code entitlementKey}
 * được sao chép từ gói lúc mua để đổi giá/gói sau này không làm đổi lịch sử.
 */
@Entity
@Table(name = "user_addons",
        indexes = @Index(name = "idx_user_addons_user_key", columnList = "user_id,entitlement_key"))
@Getter
@Setter
@NoArgsConstructor
public class UserAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private AddonPackage addonPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entitlement_key", nullable = false)
    private EntitlementKey entitlementKey;

    @Column(nullable = false)
    private long delta;

    /** Mỗi thanh toán chỉ tạo đúng một add-on. Null nếu admin tặng. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private Payment payment;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
