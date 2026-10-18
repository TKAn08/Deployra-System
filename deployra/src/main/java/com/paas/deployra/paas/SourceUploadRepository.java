package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SourceUploadRepository extends JpaRepository<SourceUpload, Long> {

    List<SourceUpload> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** Job dọn zip hết hạn. */
    List<SourceUpload> findByExpiresAtBeforeAndDeletedAtIsNull(Instant before);

    @Query("""
            select coalesce(sum(u.sizeBytes), 0L)
            from SourceUpload u
            where u.user.id = :userId
              and u.deletedAt is null
            """)
    long sumSizeBytes(@Param("userId") Long userId);
}
