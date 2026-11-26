package com.paas.deployra.api;

import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.User;
import com.paas.deployra.admin.AdminProperties;
import com.paas.deployra.api.dto.MeResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUser currentUser;
    private final AdminProperties adminProperties;

    public MeController(CurrentUser currentUser, AdminProperties adminProperties) {
        this.currentUser = currentUser;
        this.adminProperties = adminProperties;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public MeResponse me() {
        User user = currentUser.require();
        SubscriptionPlan plan = user.getSubscriptionPlan();
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                plan == null ? "free" : plan.getCode(),
                plan == null ? "Free" : plan.getName(),
                adminProperties.isAdmin(user.getEmail()));
    }
}