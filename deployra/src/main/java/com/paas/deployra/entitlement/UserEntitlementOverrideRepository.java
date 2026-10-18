package com.paas.deployra.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserEntitlementOverrideRepository extends JpaRepository<UserEntitlementOverride, Long> {

    Optional<UserEntitlementOverride> findByUserIdAndEntitlementKeyCode(Long userId, String entitlementKey);

    List<UserEntitlementOverride> findByUserId(Long userId);
}
