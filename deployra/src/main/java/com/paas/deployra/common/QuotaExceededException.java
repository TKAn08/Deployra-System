package com.paas.deployra.common;

/** Ném ra khi một thao tác vượt hạn mức của gói. Tầng API đổi thành HTTP 403. */
public class QuotaExceededException extends RuntimeException {

    private final String entitlementKey;

    public QuotaExceededException(String entitlementKey, String message) {
        super(message);
        this.entitlementKey = entitlementKey;
    }

    public String getEntitlementKey() {
        return entitlementKey;
    }
}
