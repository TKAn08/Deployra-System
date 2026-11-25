package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

  List<Deployment> findByServiceIdOrderByCreatedAtDesc(Long serviceId);

  List<Deployment> findTop50ByServiceIdOrderByCreatedAtDesc(Long serviceId);

  List<Deployment> findByStatusIn(Collection<Deployment.Status> statuses);

  Optional<Deployment> findFirstByServiceIdAndStatusOrderByCreatedAtDesc(Long serviceId, Deployment.Status status);

  /**
   * Nạp sẵn service, project, chủ sở hữu và bản upload. Worker deploy dùng entity
   * ngoài
   * transaction (open-in-view tắt) nên không được để các quan hệ LAZY chưa nạp.
   */
  @Query("""
      select d from Deployment d
        join fetch d.service s
        join fetch s.project p
        join fetch p.owner
        left join fetch d.upload
      where d.id = :id
      """)
  Optional<Deployment> findWithGraphById(@Param("id") Long id);

  boolean existsByServiceIdAndStatusIn(Long serviceId, List<Deployment.Status> statuses);

  /**
   * Tổng giây build của user trong khoảng [from, to) để tính phút build trong
   * tháng.
   */
  @Query("""
      select coalesce(sum(d.buildSeconds), 0L)
      from Deployment d
      where d.service.project.owner.id = :userId
        and d.createdAt >= :from
        and d.createdAt < :to
      """)
  long sumBuildSeconds(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

  /** Dung lượng các image còn giữ trên máy chủ của service chưa bị xoá. */
  @Query("""
      select coalesce(sum(d.imageBytes), 0L)
      from Deployment d
      where d.service.project.owner.id = :userId
        and d.service.deletedAt is null
        and d.image is not null
        and d.imagePruned = false
      """)
  long sumRetainedImageBytes(@Param("userId") Long userId);
}