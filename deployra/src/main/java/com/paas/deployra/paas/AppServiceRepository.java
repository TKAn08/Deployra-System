package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppServiceRepository extends JpaRepository<AppService, Long> {

  Optional<AppService> findBySlug(String slug);

  Optional<AppService> findByIdAndProjectOwnerIdAndDeletedAtIsNull(Long id, Long ownerId);

  List<AppService> findByProjectIdAndDeletedAtIsNull(Long projectId);

  long countByProjectOwnerIdAndTypeAndDeletedAtIsNull(Long ownerId, AppService.Type type);

  /**
   * Service của user theo trạng thái, mới deploy nhất trước (dừng bớt khi vượt
   * RAM, hết bandwidth).
   */
  List<AppService> findByProjectOwnerIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
      Long ownerId, Collection<AppService.Status> statuses);

  /** Job auto-sleep cho gói Free: service đang chạy mà lâu không có request. */
  List<AppService> findByStatusAndLastActiveAtBeforeAndDeletedAtIsNull(AppService.Status status, Instant before);

  /**
   * Tổng RAM đã cấp cho các service ở các trạng thái truyền vào (thường là
   * RUNNING, SLEEPING).
   */
  @Query("""
      select coalesce(sum(s.memoryMb), 0L)
      from AppService s
      where s.project.owner.id = :userId
        and s.deletedAt is null
        and s.status in :statuses
      """)
  long sumMemoryMb(@Param("userId") Long userId, @Param("statuses") Collection<AppService.Status> statuses);

  /** Tổng dung lượng volume (database) của user. */
  @Query("""
      select coalesce(sum(s.volumeBytes), 0L)
      from AppService s
      where s.project.owner.id = :userId
        and s.deletedAt is null
      """)
  long sumVolumeBytes(@Param("userId") Long userId);
}
