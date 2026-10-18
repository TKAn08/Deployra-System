package com.paas.deployra.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    Optional<UserSubscription> findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(
            Long userId, UserSubscription.Status status);

    List<UserSubscription> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Dùng cho job hết hạn gói: các đăng ký ACTIVE đã quá {@code currentPeriodEnd}. */
    List<UserSubscription> findByStatusAndCurrentPeriodEndBefore(UserSubscription.Status status, Instant before);
}
