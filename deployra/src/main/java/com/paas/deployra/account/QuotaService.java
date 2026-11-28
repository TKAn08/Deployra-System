/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.api.dto.QuotaDtos;
import com.paas.deployra.api.dto.QuotaDtos.PurchaseResponse;
import com.paas.deployra.entitlement.AddonPackage;
import com.paas.deployra.entitlement.AddonPackageRepository;
import com.paas.deployra.entitlement.EntitlementKey;
import com.paas.deployra.entitlement.EntitlementKeyRepository;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.Limit;
import com.paas.deployra.entitlement.QuotaLine;
import com.paas.deployra.entitlement.UserAddonRepository;
import com.paas.deployra.vnpay.VnPayService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 *
 * @author ADMIN
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AddonPackageRepository addonPackageRepository;
    private final PaymentService paymentService;
    private final VnPayService vnPayService;
    private final Clock clock;
    private final EntitlementKeyRepository keys;
    private final EntitlementService entitlements;
    private final UserAddonRepository userAddons;

    @Transactional(readOnly = true)
    public QuotaDtos.Report report(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findById(userId)
                .filter(account -> Boolean.TRUE.equals(account.getIsActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
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

    @Transactional
    public PurchaseResponse purchase(Long userId, Long addonId, String clientIp) {
        User user = userRepository.findByIdForUpdate(userId)
                .filter(account -> Boolean.TRUE.equals(account.getIsActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserSubscription currentSubscription = userSubscriptionRepository
                .findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, UserSubscription.Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản chưa có kỳ đăng ký đang hoạt động"));
        SubscriptionPlan subscriptionPlan = currentSubscription.getPlan();

        if (subscriptionPlan.getPrice().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Chỉ gói trả phí mới được mua add-on");
        }

        Instant now = clock.instant();
        if (!currentSubscription.getCurrentPeriodEnd().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Kỳ đăng ký đã hết hạn");
        }

        AddonPackage addonPackage = addonPackageRepository.findById(addonId)
                .filter(AddonPackage::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy add-on đang mở bán"));

        Payment payment = paymentService.createPendingAddonPayment(user, addonPackage);
        String paymentUrl = vnPayService.createPaymentUrl(payment, clientIp);
        return PurchaseResponse.builder()
                .paymentId(payment.getId())
                .addonId(addonPackage.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .paymentUrl(paymentUrl)
                .build();
    }
}
