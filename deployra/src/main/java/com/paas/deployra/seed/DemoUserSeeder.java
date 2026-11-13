package com.paas.deployra.seed;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo tài khoản demo để thử frontend. Không ghi đè nếu email đã tồn tại.
 * Email: demo@deployra.local — mật khẩu: demo1234
 */
@Component
@Order(200)
public class DemoUserSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@deployra.local";
    public static final String DEMO_PASSWORD = "demo1234";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DemoUserSeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(DEMO_EMAIL)) {
            return;
        }
        User user = new User();
        user.setEmail(DEMO_EMAIL);
        user.setPassword(DEMO_PASSWORD);
        user.setFirstName("Demo");
        user.setLastName("Deployra");
        user.hashPassword(passwordEncoder);
        users.save(user);
    }
}
