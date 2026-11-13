package com.paas.deployra.config;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * File mới: Spring Security dùng bean này thay user mặc định, không cần sửa SecurityConfig.
 * Đăng nhập HTTP Basic bằng email + mật khẩu trong bảng user.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản"));
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new DisabledException("Tài khoản đã bị khoá");
        }
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}
