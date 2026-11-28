/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.entitlement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 *
 * @author ADMIN
 */
public interface RamAddonExpiryRepository
        extends JpaRepository<RamAddonExpiry, Long> {

    boolean existsByUserAddonId(Long addonId);

    boolean existsByUserAddonUserIdAndStatus(
            Long userId, RamAddonExpiry.Status status);

    Optional<RamAddonExpiry> findFirstByUserAddonUserIdAndStatusOrderByDeadlineAsc(
            Long userId, RamAddonExpiry.Status status);

    List<RamAddonExpiry> findByStatusOrderByUserAddonUserIdAscIdAsc(
            RamAddonExpiry.Status status);
}
