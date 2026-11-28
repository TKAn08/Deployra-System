package com.paas.deployra.api;

import com.paas.deployra.account.QuotaService;
import com.paas.deployra.api.dto.WarningResponse;
import com.paas.deployra.api.dto.QuotaDtos;
import com.paas.deployra.entitlement.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hạn mức của người dùng đang đăng nhập. GET /api/me/quota -> báo cáo đã dùng /

 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;
    private final EntitlementService entitlementService;

    @GetMapping("/quota")
    public ResponseEntity<QuotaDtos.Report> report(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(quotaService.report(userId));
    }

    @PostMapping("/addons/{packageId}/purchase")
    public ResponseEntity<QuotaDtos.PurchaseResponse> purchase(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long packageId,
            HttpServletRequest request) {

        QuotaDtos.PurchaseResponse response = quotaService.purchase(
                userId, packageId, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }
    @GetMapping("/quota/warning")
    public ResponseEntity<WarningResponse> ramWarning(
            @AuthenticationPrincipal Long userId
    ) {
        return entitlementService.findRamWarning(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
