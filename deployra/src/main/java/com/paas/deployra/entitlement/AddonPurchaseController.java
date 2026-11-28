/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.entitlement;

import com.paas.deployra.account.dto.AddonPaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author ADMIN
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AddonPurchaseController {

    private final AddonPurchaseService addonPurchaseService;

    @PostMapping("/addons/{addonId}/purchase")
    public ResponseEntity<AddonPaymentResponse> purchase(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long addonId,
            HttpServletRequest request
    ) {
        AddonPaymentResponse response = addonPurchaseService.purchase(
                userId, addonId, request.getRemoteAddr());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
