package com.paas.deployra.catalog;

import com.paas.deployra.account.PlanEntitlement;
import com.paas.deployra.account.PlanEntitlementRepository;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.SubscriptionPlanRepository;
import com.paas.deployra.entitlement.AddonPackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Danh mục gói và add-on để hiển thị trang bảng giá. Chạy trong transaction vì
 * có quan hệ lazy.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    /** {@code limits}: khoá → giá trị; null nghĩa là không giới hạn. */
    public record PlanView(Long id, String code, String name, BigDecimal price, String currency,
            int billingPeriodDays, Map<String, Long> limits) {
    }

    public record AddonView(Long id, String name, String entitlementKey, long delta,
            String validityType, Integer validityDays, BigDecimal price) {
    }

    private final SubscriptionPlanRepository plans;
    private final PlanEntitlementRepository planEntitlements;
    private final AddonPackageRepository addonPackages;

    public CatalogService(SubscriptionPlanRepository plans,
            PlanEntitlementRepository planEntitlements,
            AddonPackageRepository addonPackages) {
        this.plans = plans;
        this.planEntitlements = planEntitlements;
        this.addonPackages = addonPackages;
    }

    public List<PlanView> listPlans() {
        return plans.findByActiveTrueOrderBySortOrderAsc().stream().map(this::toView).toList();
    }

    public List<AddonView> listAddons() {
        return addonPackages.findByActiveTrueOrderByIdAsc().stream()
                .map(a -> new AddonView(a.getId(), a.getName(), a.getEntitlementKey().getCode(), a.getDelta(),
                        a.getValidityType().name(), a.getValidityDays(), a.getPrice()))
                .toList();
    }

    private PlanView toView(SubscriptionPlan plan) {
        Map<String, Long> limits = new LinkedHashMap<>();
        planEntitlements.findByPlanId(plan.getId()).stream()
                .sorted(Comparator.comparing((PlanEntitlement pe) -> pe.getEntitlementKey().getCode()))
                .forEach(pe -> limits.put(pe.getEntitlementKey().getCode(), pe.getLimitValue()));
        return new PlanView(plan.getId(), plan.getCode(), plan.getName(), plan.getPrice(), plan.getCurrency(),
                plan.getBillingPeriodDays(), limits);
    }
}
