package com.paas.deployra.admin;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.account.dto.EntitlementOverrideRequest;
import com.paas.deployra.account.dto.EntitlementOverrideResponse;
import com.paas.deployra.entitlement.EntitlementKey;
import com.paas.deployra.entitlement.EntitlementKeyRepository;
import com.paas.deployra.entitlement.UserEntitlementOverride;
import com.paas.deployra.entitlement.UserEntitlementOverrideRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdminEntitlementService {

    private final UserRepository userRepository;
    private final EntitlementKeyRepository keyRepository;
    private final UserEntitlementOverrideRepository overrideRepository;
    private final Clock clock;

    @Transactional
    public EntitlementOverrideResponse putOverride(Long userId, String key, EntitlementOverrideRequest request) {
        EntitlementKey entitlementKey = keyRepository.findById(key)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy khóa hạn mức"));

        Long limitValue = request.limitValue();
        if ("flag".equals(entitlementKey.getUnit())
                && !Long.valueOf(0).equals(limitValue)
                && !Long.valueOf(1).equals(limitValue)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Khóa bật/tắt chỉ nhận 0 hoặc 1");
        }

        //=null thi vo han
        if (request.expiresAt() != null
                && !request.expiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Thời điểm hết hạn phải ở tương lai");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản"));

        UserEntitlementOverride override = overrideRepository
                .findByUserIdAndEntitlementKeyCode(userId, key)
                .orElseGet(UserEntitlementOverride::new);

        override.setUser(user);
        override.setEntitlementKey(entitlementKey);
        override.setLimitValue(limitValue);
        override.setReason(request.reason());
        override.setExpiresAt(request.expiresAt());

        UserEntitlementOverride saved = overrideRepository.save(override);

        return EntitlementOverrideResponse.builder()
                .id(saved.getId())
                .userId(userId)
                .key(key)
                .limitValue(saved.getLimitValue())
                .reason(saved.getReason())
                .expiresAt(saved.getExpiresAt())
                .build();
    }
}