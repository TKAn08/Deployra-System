package com.paas.deployra.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API admin. Mọi endpoint gọi AdminGuard trước; email phải nằm trong DEPLOYRA_ADMIN_EMAILS. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminGuard guard;
    private final AdminApiService admin;

    public AdminController(AdminGuard guard, AdminApiService admin) {
        this.guard = guard;
        this.admin = admin;
    }

    @GetMapping("/overview")
    public AdminDtos.Overview overview() {
        guard.requireAdmin();
        return admin.overview();
    }

    @GetMapping("/users")
    public List<AdminDtos.UserRow> users() {
        guard.requireAdmin();
        return admin.listUsers();
    }

    @GetMapping("/users/{id}")
    public AdminDtos.UserDetail user(@PathVariable Long id) {
        guard.requireAdmin();
        return admin.userDetail(id);
    }

    @PatchMapping("/users/{id}/active")
    public AdminDtos.UserRow setActive(@PathVariable Long id, @RequestBody AdminDtos.ActiveRequest request) {
        guard.requireAdmin();
        return admin.setActive(id, request.active());
    }

    @PutMapping("/users/{id}/overrides")
    public AdminDtos.OverrideView saveOverride(@PathVariable Long id,
                                               @Valid @RequestBody AdminDtos.OverrideRequest request) {
        guard.requireAdmin();
        return admin.saveOverride(id, request);
    }

    @DeleteMapping("/users/{id}/overrides/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(@PathVariable Long id, @PathVariable String key) {
        guard.requireAdmin();
        admin.deleteOverride(id, key);
    }

    @GetMapping("/services")
    public List<AdminDtos.ServiceRow> services() {
        guard.requireAdmin();
        return admin.listServices();
    }

    @GetMapping("/entitlement-keys")
    public List<AdminApiService.KeyView> keys() {
        guard.requireAdmin();
        return admin.listKeys();
    }
}