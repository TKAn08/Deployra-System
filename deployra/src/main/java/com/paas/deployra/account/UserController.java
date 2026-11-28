/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.account.dto.CurrentSubscriptionPlanResponse;
import com.paas.deployra.account.dto.ProfileResponse;
import com.paas.deployra.account.dto.WarningResponse;
import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.entitlement.QuotaLine;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author ADMIN
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SubscriptionPlanService subscriptionPlanService;
    private final EntitlementService entitlementService;

    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> profile(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.getProfile(userId));
    }

    @GetMapping("/me/subscription")
    public ResponseEntity<CurrentSubscriptionPlanResponse> currentSubscription(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(subscriptionPlanService.getCurrentSubscription(userId));
    }

    @GetMapping("/me/quota")
    public ResponseEntity<List<QuotaLine>> quota(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(entitlementService.quotaReport(userId));
    }

    @GetMapping("/me/quota/ram-warning")
    public ResponseEntity<WarningResponse> ramWarning(
            @AuthenticationPrincipal Long userId
    ) {
        return entitlementService.findRamWarning(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
