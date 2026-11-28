package com.paas.deployra.job;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.Limit;
import com.paas.deployra.entitlement.RamAddonExpiry;
import com.paas.deployra.entitlement.RamAddonExpiryRepository;
import com.paas.deployra.entitlement.UserAddon;
import com.paas.deployra.entitlement.UserAddonRepository;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.DeploymentRepository;
import com.paas.deployra.runtime.ContainerRuntime;
import com.paas.deployra.runtime.DatabaseRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author ADMIN
 */
@Component
@RequiredArgsConstructor
public class RamAddonExpiryJob {

    private final UserAddonRepository userAddonRepository;
    private final RamAddonExpiryRepository expiryRepository;
    private final UserRepository userRepository;
    private final EntitlementService entitlementService;
    private final Clock clock;
    private final AppServiceRepository appServiceRepository;
    private final DeploymentRepository deploymentRepository;
    private final ContainerRuntime containerRuntime;
    private final DatabaseRuntime databaseRuntime;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void reviewExpiredAddons() {
        List<UserAddon> expiredAddons
                = userAddonRepository.findExpiredAddonsPendingReview(
                        EntitlementCodes.MAX_TOTAL_MEMORY_MB,
                        clock.instant());

        for (UserAddon addon : expiredAddons) {
            reviewAddon(addon);
        }
    }

    private void reviewAddon(UserAddon addon) {
        Long userId = addon.getUser().getId();

        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return;
        }

        if (expiryRepository.existsByUserAddonId(addon.getId())) {
            return;
        }

        Limit limit = entitlementService.effectiveLimit(
                userId, EntitlementCodes.MAX_TOTAL_MEMORY_MB);
        long allocatedMb = entitlementService.ramAllocatedMb(userId);
        Instant checkedAt = clock.instant();

        RamAddonExpiry review = new RamAddonExpiry();
        review.setUserAddon(addon);
        review.setCheckedAt(checkedAt);
        review.setStatus(RamAddonExpiry.Status.NO_ACTION);

        boolean overQuota = !limit.unlimited() && allocatedMb > limit.value();

        if (overQuota) {
            boolean hasOpenWarning = expiryRepository
                    .existsByUserAddonUserIdAndStatus(
                            userId, RamAddonExpiry.Status.WAITING);

            if (!hasOpenWarning) {
                review.setStatus(RamAddonExpiry.Status.WAITING);
                review.setDeadline(checkedAt.plus(24, ChronoUnit.HOURS));
                review.setAllocatedMb(allocatedMb);
                review.setLimitMb(limit.value());
            }
        }

        expiryRepository.save(review);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void recheckRamWarnings() {
        for (RamAddonExpiry candidate : expiryRepository
                .findByStatusOrderByUserAddonUserIdAscIdAsc(
                        RamAddonExpiry.Status.WAITING)) {
            Long userId = candidate.getUserAddon().getUser().getId();

            if (userRepository.findByIdForUpdate(userId).isEmpty()) {
                continue;
            }

            var currentWarning = expiryRepository
                    .findFirstByUserAddonUserIdAndStatusOrderByDeadlineAsc(
                            userId, RamAddonExpiry.Status.WAITING);

            if (currentWarning.isEmpty()) {
                continue;
            }

            RamAddonExpiry warning = currentWarning.get();
            if (isWithinRamQuota(userId)) {
                warning.setStatus(RamAddonExpiry.Status.RESOLVED);
                continue;
            }

            if (clock.instant().isBefore(warning.getDeadline())) {
                continue;
            }

            //  dừng từng service 
            Limit limit = entitlementService.effectiveLimit(
                    userId, EntitlementCodes.MAX_TOTAL_MEMORY_MB);
            long remainingMb = entitlementService.ramAllocatedMb(userId);

            if (limit.unlimited() || remainingMb <= limit.value()) {
                warning.setStatus(RamAddonExpiry.Status.RESOLVED);
                continue;
            }

            List<AppService> services = appServiceRepository
                    .findByProjectOwnerIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(userId,
                            List.of(AppService.Status.RUNNING, AppService.Status.SLEEPING));

            Map<Long, Instant> deployedAt = new HashMap<>();

            for (AppService service : services) {
                Instant time = deploymentRepository
                        .findFirstByServiceIdAndStatusOrderByCreatedAtDesc(service.getId(), Deployment.Status.LIVE)
                        .map(Deployment::getFinishedAt)
                        .orElse(Instant.MIN);
                deployedAt.put(service.getId(), time);
            }

            List<AppService> orderedServices = services.stream()
                    .sorted(Comparator.comparing((AppService service) -> deployedAt.get(service.getId()))
                            .reversed()
                            .thenComparing(AppService::getId))
                    .toList();

            for (AppService service : orderedServices) {
                if (remainingMb <= limit.value()) {
                    break;
                }

                try {
                    if (service.getType() == AppService.Type.DATABASE) {
                        databaseRuntime.stop(service);
                    } else {
                        containerRuntime.stop(service);
                    }
                } catch (IllegalStateException e) {
                    break;
                }

                service.setStatus(AppService.Status.SUSPENDED);
                service.setSuspendedReason(AppService.SuspendedReason.MEMORY_OVER_QUOTA);
                service.setSuspendedAt(clock.instant());
                appServiceRepository.saveAndFlush(service);
                remainingMb -= service.getMemoryMb();
            }
            if (isWithinRamQuota(userId)) {
                warning.setStatus(RamAddonExpiry.Status.RESOLVED);
            }
        }
    }

    private boolean isWithinRamQuota(Long userId) {
        Limit limit = entitlementService.effectiveLimit(
                userId, EntitlementCodes.MAX_TOTAL_MEMORY_MB);
        long allocatedMb = entitlementService.ramAllocatedMb(userId);

        return limit.unlimited() || allocatedMb <= limit.value();
    }
}
