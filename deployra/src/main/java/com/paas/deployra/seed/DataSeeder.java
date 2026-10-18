package com.paas.deployra.seed;

import com.paas.deployra.account.PlanEntitlement;
import com.paas.deployra.account.PlanEntitlementRepository;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.SubscriptionPlanRepository;
import com.paas.deployra.entitlement.AddonPackage;
import com.paas.deployra.entitlement.AddonPackageRepository;
import com.paas.deployra.entitlement.EntitlementKey;
import com.paas.deployra.entitlement.EntitlementKeyRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.paas.deployra.entitlement.EntitlementCodes.*;

/**
 * Nạp dữ liệu danh mục khi khởi động: khoá hạn mức, 3 gói, hạn mức từng gói, 2 add-on.
 * Chạy lặp lại an toàn: dòng nào có rồi thì bỏ qua, không ghi đè giá trị đã chỉnh trong DB.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private record KeyDef(String code, String description, String unit, boolean additive) {
    }

    private record PlanDef(String code, String name, String price, int sortOrder, long[] values) {
    }

    /** Thứ tự ở đây phải khớp với thứ tự cột trong PLANS bên dưới. */
    private static final List<KeyDef> KEYS = List.of(
            new KeyDef(MAX_PROJECTS, "Số project tối đa", "count", false),
            new KeyDef(MAX_SERVICES, "Số service (app) tối đa", "count", false),
            new KeyDef(MAX_DATABASES, "Số database tối đa", "count", false),
            new KeyDef(MAX_MEMORY_MB_PER_SERVICE, "RAM tối đa mỗi service", "MB", false),
            new KeyDef(MAX_TOTAL_MEMORY_MB, "Tổng RAM của cả tài khoản (pool)", "MB", true),
            new KeyDef(MAX_STORAGE_GB, "Dung lượng lưu trữ (image, zip, volume)", "GB", false),
            new KeyDef(MAX_UPLOAD_MB, "Kích thước file zip tối đa", "MB", false),
            new KeyDef(MONTHLY_BANDWIDTH_GB, "Bandwidth mỗi tháng", "GB", true),
            new KeyDef(MONTHLY_BUILD_MINUTES, "Phút build mỗi tháng", "minutes", false),
            new KeyDef(BUILD_TIMEOUT_SECONDS, "Thời gian build tối đa", "seconds", false),
            new KeyDef(CONCURRENT_BUILDS, "Số build chạy song song", "count", false),
            new KeyDef(DEPLOYMENTS_RETAINED, "Số deployment giữ lại để rollback", "count", false),
            new KeyDef(LOG_RETENTION_DAYS, "Số ngày giữ log", "days", false),
            new KeyDef(CUSTOM_DOMAINS, "Dùng domain riêng (0/1)", "flag", false),
            new KeyDef(PRIVATE_REPOS, "Deploy repo private (0/1)", "flag", false),
            new KeyDef(AUTO_DEPLOY, "Tự deploy khi push (0/1)", "flag", false),
            new KeyDef(ALWAYS_ON, "Không tự ngủ khi không có request (0/1)", "flag", false));

    private static final List<PlanDef> PLANS = List.of(
            new PlanDef("free", "Free", "0.00", 1,
                    new long[]{1, 1, 0, 256, 256, 1, 50, 5, 100, 300, 1, 2, 1, 0, 0, 0, 0}),
            new PlanDef("starter", "Starter", "12.00", 2,
                    new long[]{3, 2, 1, 512, 1024, 10, 200, 50, 500, 600, 1, 5, 7, 0, 0, 1, 1}),
            new PlanDef("pro", "Pro", "29.00", 3,
                    new long[]{10, 5, 3, 1024, 2048, 30, 500, 150, 2000, 900, 2, 10, 30, 1, 1, 1, 1}));

    private final EntitlementKeyRepository keys;
    private final SubscriptionPlanRepository plans;
    private final PlanEntitlementRepository planEntitlements;
    private final AddonPackageRepository addonPackages;

    public DataSeeder(EntitlementKeyRepository keys,
                      SubscriptionPlanRepository plans,
                      PlanEntitlementRepository planEntitlements,
                      AddonPackageRepository addonPackages) {
        this.keys = keys;
        this.plans = plans;
        this.planEntitlements = planEntitlements;
        this.addonPackages = addonPackages;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedKeys();
        seedPlans();
        seedAddons();
    }

    private void seedKeys() {
        for (KeyDef def : KEYS) {
            if (!keys.existsById(def.code())) {
                keys.save(new EntitlementKey(def.code(), def.description(), def.unit(), def.additive()));
            }
        }
    }

    private void seedPlans() {
        for (PlanDef def : PLANS) {
            SubscriptionPlan plan = plans.findByCode(def.code()).orElseGet(() -> {
                SubscriptionPlan created = new SubscriptionPlan();
                created.setCode(def.code());
                created.setName(def.name());
                created.setPrice(new BigDecimal(def.price()));
                created.setSortOrder(def.sortOrder());
                return plans.save(created);
            });
            for (int i = 0; i < KEYS.size(); i++) {
                String keyCode = KEYS.get(i).code();
                if (planEntitlements.findByPlanIdAndEntitlementKeyCode(plan.getId(), keyCode).isEmpty()) {
                    PlanEntitlement entitlement = new PlanEntitlement();
                    entitlement.setPlan(plan);
                    entitlement.setEntitlementKey(keys.getReferenceById(keyCode));
                    entitlement.setLimitValue(def.values()[i]);
                    planEntitlements.save(entitlement);
                }
            }
        }
    }

    private void seedAddons() {
        seedAddon("RAM +512MB (30 ngày)", MAX_TOTAL_MEMORY_MB, 512,
                AddonPackage.ValidityType.DAYS, 30, "6.00");
        seedAddon("Bandwidth +50GB (đến hết tháng)", MONTHLY_BANDWIDTH_GB, 50,
                AddonPackage.ValidityType.CALENDAR_MONTH, null, "8.00");
    }

    private void seedAddon(String name, String keyCode, long delta,
                           AddonPackage.ValidityType validityType, Integer validityDays, String price) {
        if (addonPackages.existsByName(name)) {
            return;
        }
        AddonPackage addon = new AddonPackage();
        addon.setName(name);
        addon.setEntitlementKey(keys.getReferenceById(keyCode));
        addon.setDelta(delta);
        addon.setValidityType(validityType);
        addon.setValidityDays(validityDays);
        addon.setPrice(new BigDecimal(price));
        addonPackages.save(addon);
    }
}
