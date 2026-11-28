package com.paas.deployra.api;

import com.paas.deployra.account.AuthService;
import com.paas.deployra.api.dto.LoginRequest;
import com.paas.deployra.api.dto.LoginResponse;
import com.paas.deployra.api.dto.RegisterRequest;
import com.paas.deployra.api.dto.RegisterResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Đăng ký tài khoản. Người dùng mới chưa có gói (subscriptionPlan = null) nên
 * mặc định ở gói Free. Sau khi đăng ký, frontend tự đăng nhập bằng email và
 * mật khẩu vừa nhập.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(authorization.substring(7));
        return ResponseEntity.noContent().build();
    }
}
