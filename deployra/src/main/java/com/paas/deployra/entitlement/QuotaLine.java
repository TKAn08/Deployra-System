package com.paas.deployra.entitlement;

/** Một dòng báo cáo: đã dùng bao nhiêu trên hạn mức nào. {@code used} cùng đơn vị với {@code limit}. */
public record QuotaLine(String key, Limit limit, long used) {
}
