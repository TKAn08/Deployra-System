/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import com.paas.deployra.api.dto.LoginRequest;
import com.paas.deployra.api.dto.LoginResponse;
import com.paas.deployra.api.dto.RegisterRequest;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.paas.deployra.api.dto.RegisterResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 *
 * @author ADMIN
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {

        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(request.password());
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());

        user.hashPassword(passwordEncoder);

        User savedUser = userRepository.saveAndFlush(user);

        return RegisterResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .build();

    }

    @Transactional
    public LoginResponse login(LoginRequest request) {

        String email = request.email()
                .trim()
                .toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email không đúng"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Mật khẩu không đúng");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị vô hiệu hóa");
        }

        user.updateLastLogin();
        String token = jwtService.generateToken(user);

        return LoginResponse.builder()
                .accessToken(token)
                .expiresIn(jwtService.getExpirationSeconds())
                .role(user.getRole())
                .build();

    }

    @Transactional
    public void logout(String token) {
        Claims claims = jwtService.parse(token);
        revokedTokenRepository.save(new RevokedToken(claims.getId(), claims.getExpiration().toInstant()));
    }
}
