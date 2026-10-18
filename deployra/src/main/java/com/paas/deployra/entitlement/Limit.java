package com.paas.deployra.entitlement;

/** Hạn mức đã tính xong: không giới hạn, hoặc một con số (đã cộng add-on nếu khoá cho phép cộng). */
public record Limit(boolean unlimited, long value) {

    public static final Limit UNLIMITED = new Limit(true, 0);

    public static Limit of(long value) {
        return new Limit(false, value);
    }

    /** Còn đủ chỗ cho thêm {@code requested} khi đã dùng {@code used}? */
    public boolean allows(long used, long requested) {
        return unlimited || used + requested <= value;
    }

    /** Dùng cho tính năng bật/tắt (private_repos, always_on...): 0 là tắt. */
    public boolean enabled() {
        return unlimited || value > 0;
    }
}
