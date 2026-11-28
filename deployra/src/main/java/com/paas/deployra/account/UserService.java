/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.api.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 *
 * @author ADMIN
 */
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    
    public MeResponse getProfile(Long userId) {
    User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại"));

    if (!Boolean.TRUE.equals(user.getIsActive())) {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Tài khoản đã bị vô hiệu hóa");
    }

    SubscriptionPlan plan = user.getSubscriptionPlan();
    
    return MeResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .planCode(plan == null ? "free" : plan.getCode())
            .planName(plan == null ? "Free" : plan.getName())
            .admin(user.getRole() == User.Role.ROLE_ADMIN)
            .build();
    }
}
