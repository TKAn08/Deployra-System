package com.paas.deployra.entitlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Danh mục các loại hạn mức (max_projects, monthly_bandwidth_gb...).
 * {@code defaultLimit} = 0 nên khoá nào gói không khai báo thì bị từ chối.
 * {@code additive} = true thì add-on được cộng thêm vào (RAM, bandwidth).
 */
@Entity
@Table(name = "entitlement_keys")
@Getter
@Setter
@NoArgsConstructor
public class EntitlementKey {

    @Id
    @Column(length = 64)
    private String code;

    private String description;

    @Column(length = 20)
    private String unit;

    @Column(name = "default_limit", nullable = false)
    private long defaultLimit = 0;

    @Column(name = "is_additive", nullable = false)
    private boolean additive = false;

    public EntitlementKey(String code, String description, String unit, boolean additive) {
        this.code = code;
        this.description = description;
        this.unit = unit;
        this.additive = additive;
    }
}
