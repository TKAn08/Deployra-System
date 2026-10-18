package com.paas.deployra.paas;

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

import java.time.LocalDate;

/** Lưu lượng ra (byte) của mỗi service theo ngày (UTC), lấy từ metrics của Traefik. */
@Entity
@Table(name = "bandwidth_usage_daily",
        uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "usage_date"}))
@Getter
@Setter
@NoArgsConstructor
public class BandwidthUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private AppService service;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "bytes_out", nullable = false)
    private long bytesOut = 0;
}
