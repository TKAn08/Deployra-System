package com.paas.deployra.entitlement;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Gói bán thêm, ví dụ "+512MB RAM trong 30 ngày", "+50GB bandwidth đến hết tháng". */
@Entity
@Table(name = "addon_packages")
@Getter
@Setter
@NoArgsConstructor
public class AddonPackage {

    /** DAYS: hiệu lực {@code validityDays} ngày. CALENDAR_MONTH: hết hiệu lực vào cuối tháng dương lịch. */
    public enum ValidityType { DAYS, CALENDAR_MONTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entitlement_key", nullable = false)
    private EntitlementKey entitlementKey;

    @Column(nullable = false)
    private long delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_type", nullable = false, length = 20)
    private ValidityType validityType = ValidityType.DAYS;

    @Column(name = "validity_days")
    private Integer validityDays;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
