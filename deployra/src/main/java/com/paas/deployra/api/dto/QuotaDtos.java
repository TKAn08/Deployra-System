package com.paas.deployra.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class QuotaDtos {

    private QuotaDtos() {
    }

    public record Line(String key, String description, String unit, boolean unlimited, Long limit, long used) {
    }

    public record Report(String planCode, String planName, List<Line> lines, List<Addon> activeAddons) {
    }

    public record Addon(Long id, String name, String entitlementKey, long delta, Instant expiresAt) {
    }

    public record PurchaseResponse(Long paymentId, Long addonId, Instant expiresAt, BigDecimal amount, String currency) {
    }
}
