package com.paas.deployra.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddonPackageRepository extends JpaRepository<AddonPackage, Long> {

    List<AddonPackage> findByActiveTrueOrderByIdAsc();

    boolean existsByName(String name);

    Optional<AddonPackage> findFirstByEntitlementKeyCodeAndActiveTrue(String entitlementKey);
}
