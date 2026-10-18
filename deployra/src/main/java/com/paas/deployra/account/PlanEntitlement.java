package com.paas.deployra.account;

import com.paas.deployra.entitlement.EntitlementKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Hạn mức của một gói cho một khoá. {@code limitValue} = null nghĩa là không giới hạn (và bỏ qua add-on). */
@Entity
@Table(name = "plan_entitlements",
        uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "entitlement_key"}))
@Getter
@Setter
@NoArgsConstructor
public class PlanEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entitlement_key", nullable = false)
    private EntitlementKey entitlementKey;

    @Column(name = "limit_value")
    private Long limitValue;
}
