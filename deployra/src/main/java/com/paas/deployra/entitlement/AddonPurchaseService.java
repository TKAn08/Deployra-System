/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.entitlement;

import com.paas.deployra.account.Payment;
import com.paas.deployra.account.PaymentService;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.account.UserSubscription;
import com.paas.deployra.account.UserSubscriptionRepository;
import com.paas.deployra.account.dto.AddonPaymentResponse;
import com.paas.deployra.vnpay.VnPayService;
import java.time.Clock;
import java.time.Instant;
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
public class AddonPurchaseService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AddonPackageRepository addonPackageRepository;
    private final PaymentService paymentService;
    private final VnPayService vnPayService;
    private final Clock clock;

    @Transactional
    public AddonPaymentResponse purchase(Long userId, Long addonId, String clientIp) {
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
        return AddonPaymentResponse.builder()
                .paymentId(payment.getId())
                .addonId(addonPackage.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .paymentUrl(paymentUrl)
                .build();
    }
}
