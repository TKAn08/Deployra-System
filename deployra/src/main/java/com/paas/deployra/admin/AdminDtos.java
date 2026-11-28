package com.paas.deployra.admin;

import com.paas.deployra.paas.AppService;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record Overview(long users, long projects, long runningServices, long failedServices) {
    }

    public record UserRow(
            Long id,
            String email,
            String firstName,
            String lastName,
            Boolean active,
            String planCode,
            Instant createdAt,
            boolean admin
    ) {
    }

    public record ActiveRequest(boolean active) {
    }

    public record OverrideView(Long id, String entitlementKey, Long limitValue, String reason, Instant expiresAt) {
    }

    public record OverrideRequest(
            @NotBlank String entitlementKey,
            Long limitValue,
            String reason,
            Instant expiresAt
    ) {
    }

    public record ServiceRow(
            Long id,
            String name,
            String slug,
            String ownerEmail,
            Long projectId,
            String projectName,
            AppService.Type type,
            AppService.Status status,
            AppService.SuspendedReason suspendedReason,
            int memoryMb
    ) {
    }

    public record UserDetail(UserRow user, List<OverrideView> overrides) {
    }
}
