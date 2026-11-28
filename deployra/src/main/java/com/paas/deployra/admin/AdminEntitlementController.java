package com.paas.deployra.admin;

import com.paas.deployra.account.dto.EntitlementOverrideRequest;
import com.paas.deployra.account.dto.EntitlementOverrideResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminEntitlementController {

    private final AdminEntitlementService adminEntitlementService;

    @PutMapping("/{userId}/entitlement-overrides/{key}")
    public ResponseEntity<EntitlementOverrideResponse> putOverride(
            @PathVariable Long userId,
            @PathVariable String key,
            @Valid @RequestBody EntitlementOverrideRequest request
    ) {
        return ResponseEntity.ok(adminEntitlementService.putOverride(userId, key, request));
    }
}
