package com.paas.deployra.admin;

import com.paas.deployra.account.User;
import com.paas.deployra.api.CurrentUser;
import org.springframework.stereotype.Component;

@Component
public class AdminGuard {

    private final CurrentUser currentUser;
    private final AdminProperties properties;

    public AdminGuard(CurrentUser currentUser, AdminProperties properties) {
        this.currentUser = currentUser;
        this.properties = properties;
    }

    public User requireAdmin() {
        User user = currentUser.require();
        if (!properties.isAdmin(user.getEmail())) {
            throw new AdminForbiddenException();
        }
        return user;
    }

    public boolean isAdmin(String email) {
        return properties.isAdmin(email);
    }
}
