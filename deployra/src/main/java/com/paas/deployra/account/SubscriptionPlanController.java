/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.account.dto.CurrentSubscriptionPlanResponse;
import com.paas.deployra.account.dto.PaymentResponse;
import com.paas.deployra.account.dto.SubscibePlanRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author ADMIN
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SubscriptionPlanController {

    private final SubscriptionPlanService subscriptionPlanService;

    @PostMapping("/subscribe")
    public ResponseEntity<PaymentResponse> subscribe(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody SubscibePlanRequest request,
            HttpServletRequest httpRequest) {

        PaymentResponse response = subscriptionPlanService.subscribe(userId, request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
