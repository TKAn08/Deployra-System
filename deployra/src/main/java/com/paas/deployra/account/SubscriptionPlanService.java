/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.api.dto.PaymentResponse;
import com.paas.deployra.api.dto.SubscibePlanRequest;
import com.paas.deployra.vnpay.VnPayService;
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
public class SubscriptionPlanService {

    private final UserRepository userRepository;
    private final SubscriptionPlanRepository planRepository;
    private final PaymentService paymentService;
    private final VnPayService vnPayService;

    @Transactional
    public PaymentResponse subscribe(Long userId, SubscibePlanRequest request, String clientIp) {
        User user = userRepository.findById(userId)
                .filter(account -> Boolean.TRUE.equals(account.getIsActive()))
                .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED));

        String planCode = request.planCode().strip().toLowerCase();
        SubscriptionPlan plan = planRepository.findByCode(planCode)
                .filter(SubscriptionPlan::isActive)
                .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Không tìm thấy gói đang mở bán"));

        if ("free".equals(plan.getCode())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Hãy chọn một gói trả phí");
        }

        SubscriptionPlan currentPlan = user.getSubscriptionPlan();
        String currentPlanCode = currentPlan == null ? "free" : currentPlan.getCode();

        if (plan.getCode().equals(currentPlanCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản đang sử dụng gói này");
        }

        Payment payment = paymentService.createPendingPlanPayment(user, plan);
        String paymentUrl = vnPayService.createPaymentUrl(payment, clientIp);
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .planCode(plan.getCode())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .paymentUrl(paymentUrl)
                .build();
    }
}
