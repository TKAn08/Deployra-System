package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Người dùng đang gọi API.
 *
 * <p>Ưu tiên 1: Spring Security đã xác thực (SecurityConfig bật HTTP Basic).
 * <p>Ưu tiên 2: SecurityConfig đang mở anonymous (chế độ chạy thử của deploy engine)
 * thì không có ai được xác thực, nên tự đọc header {@code Authorization: Basic ...}
 * và kiểm tra email + mật khẩu. Nhờ vậy đăng nhập, hạn mức và trang admin vẫn chạy
 * mà không phải sửa SecurityConfig.
 */
@Component
public class CurrentUser {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public CurrentUser(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public User require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return users.findByEmail(authentication.getName())
                    .orElseThrow(() -> new ApiUnauthorizedException("Không tìm thấy tài khoản"));
        }
        return fromBasicHeader();
    }

    private User fromBasicHeader() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            throw new ApiUnauthorizedException("Chưa đăng nhập");
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw new ApiUnauthorizedException("Chưa đăng nhập");
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiUnauthorizedException("Thông tin đăng nhập không hợp lệ");
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            throw new ApiUnauthorizedException("Thông tin đăng nhập không hợp lệ");
        }
        String email = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);

        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiUnauthorizedException("Sai email hoặc mật khẩu"));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new ApiUnauthorizedException("Tài khoản đã bị khoá");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiUnauthorizedException("Sai email hoặc mật khẩu");
        }
        return user;
    }
}
