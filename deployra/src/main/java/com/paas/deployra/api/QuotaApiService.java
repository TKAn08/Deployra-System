package com.paas.deployra.api;

import com.paas.deployra.account.Payment;
import com.paas.deployra.account.PaymentRepository;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.User;
import com.paas.deployra.api.dto.QuotaDtos;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.entitlement.AddonPackage;
import com.paas.deployra.entitlement.AddonPackageRepository;
import com.paas.deployra.entitlement.EntitlementKey;
import com.paas.deployra.entitlement.EntitlementKeyRepository;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.Limit;
import com.paas.deployra.entitlement.QuotaLine;
import com.paas.deployra.entitlement.UserAddon;
import com.paas.deployra.entitlement.UserAddonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class QuotaApiService {

    private final CurrentUser currentUser;
    private final EntitlementService entitlements;
    private final EntitlementKeyRepository keys;
    private final UserAddonRepository userAddons;
    private final AddonPackageRepository addonPackages;
    private final PaymentRepository payments;
    private final Clock clock;

    public QuotaApiService(CurrentUser currentUser,
                           EntitlementService entitlements,
                           EntitlementKeyRepository keys,
                           UserAddonRepository userAddons,
                           AddonPackageRepository addonPackages,
                           PaymentRepository payments,
                           Clock clock) {
        this.currentUser = currentUser;
        this.entitlements = entitlements;
        this.keys = keys;
        this.userAddons = userAddons;
        this.addonPackages = addonPackages;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuotaDtos.Report report() {
        User user = currentUser.require();
        SubscriptionPlan plan = user.getSubscriptionPlan();
        List<QuotaDtos.Line> lines = entitlements.quotaReport(user.getId()).stream()
                .map(this::toLine)
                .toList();
        Instant now = clock.instant();
        List<QuotaDtos.Addon> addons = userAddons
                .findByUserIdAndExpiresAtAfterOrderByExpiresAtAsc(user.getId(), now)
                .stream()
                .map(a -> new QuotaDtos.Addon(
                        a.getId(),
                        a.getAddonPackage().getName(),
                        a.getEntitlementKey().getCode(),
                        a.getDelta(),
                        a.getExpiresAt()))
                .toList();
        return new QuotaDtos.Report(
                plan == null ? "free" : plan.getCode(),
                plan == null ? "Free" : plan.getName(),
                lines,
                addons);
    }

    @Transactional
    public QuotaDtos.PurchaseResponse purchase(Long packageId) {
        User user = currentUser.require();
        requirePaidPlan(user);
        AddonPackage pack = addonPackages.findById(packageId)
                .filter(AddonPackage::isActive)
                .orElseThrow(() -> new ApiNotFoundException("Không tìm thấy gói add-on"));

        Instant now = clock.instant();
        Instant expiresAt = expiresAt(pack, now);

        Payment payment = new Payment();
        payment.setUser(user);
        payment.setPurpose(Payment.Purpose.ADDON);
        payment.setAmount(pack.getPrice());
        payment.setCurrency("USD");
        payment.setStatus(Payment.Status.SUCCEEDED);
        payment.setProvider("SIMULATED");
        payment.setProviderTxnId("sim-" + UUID.randomUUID());
        payments.save(payment);

        UserAddon addon = new UserAddon();
        addon.setUser(user);
        addon.setAddonPackage(pack);
        addon.setEntitlementKey(pack.getEntitlementKey());
        addon.setDelta(pack.getDelta());
        addon.setPayment(payment);
        addon.setStartsAt(now);
        addon.setExpiresAt(expiresAt);
        userAddons.save(addon);

        return new QuotaDtos.PurchaseResponse(payment.getId(), addon.getId(), expiresAt, pack.getPrice(), "USD");
    }

    private QuotaDtos.Line toLine(QuotaLine line) {
        EntitlementKey key = keys.findById(line.key()).orElse(null);
        Limit limit = line.limit();
        return new QuotaDtos.Line(
                line.key(),
                key == null ? line.key() : key.getDescription(),
                key == null ? "" : key.getUnit(),
                limit.unlimited(),
                limit.unlimited() ? null : limit.value(),
                line.used());
    }

    private static void requirePaidPlan(User user) {
        SubscriptionPlan plan = user.getSubscriptionPlan();
        if (plan == null || "free".equalsIgnoreCase(plan.getCode()) || plan.getPrice().signum() == 0) {
            throw new QuotaExceededException("addon",
                    "Chỉ gói trả phí mới mua được add-on. Hãy nâng lên Starter hoặc Pro.");
        }
    }

    private static Instant expiresAt(AddonPackage pack, Instant now) {
        if (pack.getValidityType() == AddonPackage.ValidityType.CALENDAR_MONTH) {
            return YearMonth.from(now.atZone(ZoneOffset.UTC))
                    .plusMonths(1)
                    .atDay(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        int days = pack.getValidityDays() == null ? 30 : pack.getValidityDays();
        return now.plus(Duration.ofDays(days));
    }
}
