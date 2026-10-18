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

/** Biến môi trường của service; giá trị luôn được mã hoá (xem SecretCipher). */
@Entity
@Table(name = "variables",
        uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "var_key"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvVariable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private AppService service;

    @Column(name = "var_key", nullable = false)
    private String varKey;

    @Column(name = "value_encrypted", nullable = false, columnDefinition = "text")
    private String valueEncrypted;
}
