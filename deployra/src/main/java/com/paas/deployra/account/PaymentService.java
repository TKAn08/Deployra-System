/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.entitlement.AddonPackage;
import com.paas.deployra.entitlement.AddonPackageRepository;
import com.paas.deployra.entitlement.UserAddon;
import com.paas.deployra.entitlement.UserAddonRepository;
import com.paas.deployra.vnpay.VnPayService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author ADMIN
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final VnPayService vnPayService;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final Clock clock;
    private final UserAddonRepository userAddonRepository;

    @Transactional
    public Payment createPendingPlanPayment(User user, SubscriptionPlan plan) {
        Payment payment = new Payment();
        payment.setUser(user);
        payment.setPlan(plan);
        payment.setPurpose(Payment.Purpose.PLAN);
        payment.setAmount(plan.getPrice());
        payment.setCurrency(plan.getCurrency());
        payment.setProvider("VNPAY");
        payment.setStatus(Payment.Status.PENDING);

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment createPendingAddonPayment(User user, AddonPackage addonPackage) {
        Payment payment = new Payment();
        payment.setUser(user);
        payment.setAddonPackage(addonPackage);
        payment.setPurpose(Payment.Purpose.ADDON);
        payment.setAmount(addonPackage.getPrice());
        payment.setCurrency("VND");
        payment.setProvider("VNPAY");
        payment.setStatus(Payment.Status.PENDING);

        return paymentRepository.save(payment);
    }

    @Transactional
    public String processVnPayIpn(Map<String, String> params) {
        if (!vnPayService.verifyIpn(params)) {
            return "97";
        }
        long paymentId;
        try {
            paymentId = Long.parseLong(params.get("vnp_TxnRef"));
        } catch (NumberFormatException ex) {
            return "01";
        }

        String amountParam = params.get("vnp_Amount");
        if (amountParam == null || !amountParam.matches("[0-9]+")) {
            return "04";
        }
        BigDecimal receivedAmount = new BigDecimal(amountParam);

        Optional<Payment> foundPayment = paymentRepository.findByIdForUpdate(paymentId)
                .filter(payment -> "VNPAY".equals(payment.getProvider()));

        if (foundPayment.isEmpty()) {
            return "01";
        }

        Payment payment = foundPayment.get();

        if (payment.getAmount().movePointRight(2).compareTo(receivedAmount) != 0) {
            return "04";
        }

        if (payment.getStatus() != Payment.Status.PENDING) {
            return "02";
        }

        String transactionStatus = params.get("vnp_TransactionStatus");
        if ("02".equals(transactionStatus)) {
            payment.setStatus(Payment.Status.FAILED);
            return "00";
        }

        if (!"00".equals(params.get("vnp_ResponseCode")) || !"00".equals(transactionStatus)) {
            return "99";
        }

        String transactionNo = params.get("vnp_TransactionNo");
        if (transactionNo == null || transactionNo.isBlank()) {
            return "99";
        }
        Instant now = clock.instant();

        switch (payment.getPurpose()) {
            case PLAN -> createUserSubscription(payment, now);
            case ADDON -> createUserAddon(payment, now);
        }
        payment.setProviderTxnId(transactionNo);
        payment.setStatus(Payment.Status.SUCCEEDED);

        return "00";
    }

    private void createUserSubscription(Payment payment, Instant now) {
        User user = userRepository.findByIdForUpdate(payment.getUser().getId())
                .orElseThrow();
        SubscriptionPlan newPlan = payment.getPlan();

        closePreviousSubscription(user.getId(), now);

        UserSubscription subscription = new UserSubscription();
        subscription.setUser(user);
        subscription.setPlan(newPlan);
        subscription.setPayment(payment);
        subscription.setStatus(UserSubscription.Status.ACTIVE);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(
                now.plus(newPlan.getBillingPeriodDays(), ChronoUnit.DAYS));

        userSubscriptionRepository.save(subscription);
        user.setSubscriptionPlan(newPlan);
    }

    private void createUserAddon(Payment payment, Instant now) {
        AddonPackage addonPackage = payment.getAddonPackage();
        if (addonPackage == null) {
            throw new IllegalStateException("Giao dịch thiếu thông tin add-on");
        }

        Instant expiresAt = switch (addonPackage.getValidityType()) {
            case DAYS ->
                now.plus(addonPackage.getValidityDays(), ChronoUnit.DAYS);
            case CALENDAR_MONTH ->
                YearMonth.from(now.atZone(ZoneOffset.UTC))
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        };

        UserAddon userAddon = new UserAddon();
        userAddon.setUser(payment.getUser());
        userAddon.setAddonPackage(addonPackage);
        userAddon.setEntitlementKey(addonPackage.getEntitlementKey());
        userAddon.setDelta(addonPackage.getDelta());
        userAddon.setPayment(payment);
        userAddon.setStartsAt(now);
        userAddon.setExpiresAt(expiresAt);

        userAddonRepository.save(userAddon);
    }

    private void closePreviousSubscription(Long userId, Instant now) {
        Optional<UserSubscription> activeSubscription = userSubscriptionRepository
                .findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, UserSubscription.Status.ACTIVE);

        if (activeSubscription.isEmpty()) {
            return;
        }

        UserSubscription previousSubscription = activeSubscription.get();

        if (previousSubscription.getCurrentPeriodEnd().isAfter(now)) {
            previousSubscription.setStatus(UserSubscription.Status.CANCELLED);
        } else {
            previousSubscription.setStatus(UserSubscription.Status.EXPIRED);
        }
    }
}
