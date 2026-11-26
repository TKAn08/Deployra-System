package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Đăng ký tài khoản. Người dùng mới chưa có gói (subscriptionPlan = null) nên
 * mặc định ở gói Free. Sau khi đăng ký, frontend tự đăng nhập bằng email và
 * mật khẩu vừa nhập.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            // BCrypt chỉ dùng tối đa 72 byte đầu của mật khẩu
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName) {
    }

    public record RegisterResponse(Long id, String email) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<Object> register(@Valid @RequestBody RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            return emailTaken();
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(blankToNull(request.firstName()));
        user.setLastName(blankToNull(request.lastName()));

        User saved;
        try {
            saved = users.save(user);
        } catch (DataIntegrityViolationException e) {
            // hai request đăng ký cùng email gần như đồng thời
            return emailTaken();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(saved.getId(), saved.getEmail()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseEntity<Object> emailTaken() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "email_taken");
        body.put("message", "Email này đã được đăng ký");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
