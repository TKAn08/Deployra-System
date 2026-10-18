package com.paas.deployra.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, Long> {

    List<PlanEntitlement> findByPlanId(Long planId);

    Optional<PlanEntitlement> findByPlanIdAndEntitlementKeyCode(Long planId, String entitlementKey);
}
