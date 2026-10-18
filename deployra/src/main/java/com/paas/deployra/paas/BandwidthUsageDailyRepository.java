package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface BandwidthUsageDailyRepository extends JpaRepository<BandwidthUsageDaily, Long> {

    Optional<BandwidthUsageDaily> findByServiceIdAndUsageDate(Long serviceId, LocalDate usageDate);

    /** Bandwidth cả tài khoản trong [from, to). Tính cả service đã xoá mềm vì lưu lượng đó đã phát sinh. */
    @Query("""
            select coalesce(sum(b.bytesOut), 0L)
            from BandwidthUsageDaily b
            where b.service.project.owner.id = :userId
              and b.usageDate >= :from
              and b.usageDate < :to
            """)
    long sumBytesOut(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
