package com.paas.deployra.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserAddonRepository extends JpaRepository<UserAddon, Long> {

    /** Tổng delta của các add-on đang hiệu lực (starts_at <= now < expires_at) cho một khoá. */
    @Query("""
            select coalesce(sum(a.delta), 0L)
            from UserAddon a
            where a.user.id = :userId
              and a.entitlementKey.code = :key
              and a.startsAt <= :now
              and a.expiresAt > :now
            """)
    long sumActiveDelta(@Param("userId") Long userId, @Param("key") String key, @Param("now") Instant now);

    List<UserAddon> findByUserIdAndExpiresAtAfterOrderByExpiresAtAsc(Long userId, Instant after);

    /** Dùng cho job báo hết hạn add-on RAM. */
    List<UserAddon> findByExpiresAtBetween(Instant from, Instant to);

    Optional<UserAddon> findByPaymentId(Long paymentId);
}
