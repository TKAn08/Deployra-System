package com.paas.deployra.entitlement;

/** Mã các khoá hạn mức. Giá trị phải khớp với bảng entitlement_keys (xem DataSeeder). */
public final class EntitlementCodes {

    private EntitlementCodes() {
    }

    public static final String MAX_PROJECTS = "max_projects";
    public static final String MAX_SERVICES = "max_services";
    public static final String MAX_DATABASES = "max_databases";
    public static final String MAX_MEMORY_MB_PER_SERVICE = "max_memory_mb_per_service";
    /** Cộng được add-on. */
    public static final String MAX_TOTAL_MEMORY_MB = "max_total_memory_mb";
    public static final String MAX_STORAGE_GB = "max_storage_gb";
    public static final String MAX_UPLOAD_MB = "max_upload_mb";
    /** Cộng được add-on. */
    public static final String MONTHLY_BANDWIDTH_GB = "monthly_bandwidth_gb";
    public static final String MONTHLY_BUILD_MINUTES = "monthly_build_minutes";
    public static final String BUILD_TIMEOUT_SECONDS = "build_timeout_seconds";
    public static final String CONCURRENT_BUILDS = "concurrent_builds";
    public static final String DEPLOYMENTS_RETAINED = "deployments_retained";
    public static final String LOG_RETENTION_DAYS = "log_retention_days";
    public static final String CUSTOM_DOMAINS = "custom_domains";
    public static final String PRIVATE_REPOS = "private_repos";
    public static final String AUTO_DEPLOY = "auto_deploy";
    public static final String ALWAYS_ON = "always_on";
}
