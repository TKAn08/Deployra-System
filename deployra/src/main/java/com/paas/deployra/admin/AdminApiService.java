package com.paas.deployra.admin;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.api.ApiNotFoundException;
import com.paas.deployra.entitlement.EntitlementKey;
import com.paas.deployra.entitlement.EntitlementKeyRepository;
import com.paas.deployra.entitlement.UserEntitlementOverride;
import com.paas.deployra.entitlement.UserEntitlementOverrideRepository;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.ProjectRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Nghiệp vụ cho trang admin. Chỉ đọc/ghi qua repository sẵn có, không đổi entity. */
@Service
public class AdminApiService {

    public record KeyView(String code, String description, String unit) {
    }

    private final UserRepository users;
    private final ProjectRepository projects;
    private final AppServiceRepository services;
    private final UserEntitlementOverrideRepository overrides;
    private final EntitlementKeyRepository keys;
    private final AdminProperties adminProperties;

    public AdminApiService(UserRepository users,
                           ProjectRepository projects,
                           AppServiceRepository services,
                           UserEntitlementOverrideRepository overrides,
                           EntitlementKeyRepository keys,
                           AdminProperties adminProperties) {
        this.users = users;
        this.projects = projects;
        this.services = services;
        this.overrides = overrides;
        this.keys = keys;
        this.adminProperties = adminProperties;
    }

    @Transactional(readOnly = true)
    public AdminDtos.Overview overview() {
        long projectCount = projects.findAll().stream().filter(p -> p.getDeletedAt() == null).count();
        List<AppService> live = services.findAll().stream().filter(s -> s.getDeletedAt() == null).toList();
        long running = live.stream().filter(s -> s.getStatus() == AppService.Status.RUNNING).count();
        long failed = live.stream().filter(s -> s.getStatus() == AppService.Status.FAILED).count();
        return new AdminDtos.Overview(users.count(), projectCount, running, failed);
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.UserRow> listUsers() {
        return users.findAll(Sort.by("id")).stream().map(this::toRow).toList();
    }

    @Transactional(readOnly = true)
    public AdminDtos.UserDetail userDetail(Long id) {
        User user = requireUser(id);
        List<AdminDtos.OverrideView> list = overrides.findByUserId(id).stream().map(this::toView).toList();
        return new AdminDtos.UserDetail(toRow(user), list);
    }

    @Transactional
    public AdminDtos.UserRow setActive(Long id, boolean active) {
        User user = requireUser(id);
        if (!active && adminProperties.isAdmin(user.getEmail())) {
            throw new IllegalArgumentException("Không thể khoá tài khoản admin");
        }
        user.setIsActive(active);
        users.save(user);
        return toRow(user);
    }

    @Transactional
    public AdminDtos.OverrideView saveOverride(Long userId, AdminDtos.OverrideRequest request) {
        User user = requireUser(userId);
        EntitlementKey key = keys.findById(request.entitlementKey())
                .orElseThrow(() -> new IllegalArgumentException("Khoá hạn mức không tồn tại: " + request.entitlementKey()));
        UserEntitlementOverride override = overrides
                .findByUserIdAndEntitlementKeyCode(userId, key.getCode())
                .orElseGet(() -> {
                    UserEntitlementOverride created = new UserEntitlementOverride();
                    created.setUser(user);
                    created.setEntitlementKey(key);
                    return created;
                });
        override.setLimitValue(request.limitValue());
        override.setReason(request.reason());
        override.setExpiresAt(request.expiresAt());
        overrides.save(override);
        return toView(override);
    }

    @Transactional
    public void deleteOverride(Long userId, String key) {
        UserEntitlementOverride override = overrides.findByUserIdAndEntitlementKeyCode(userId, key)
                .orElseThrow(() -> new ApiNotFoundException("Không tìm thấy override"));
        overrides.delete(override);
    }

    @Transactional(readOnly = true)
    public List<AdminDtos.ServiceRow> listServices() {
        return services.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(s -> s.getDeletedAt() == null)
                .map(s -> new AdminDtos.ServiceRow(
                        s.getId(),
                        s.getName(),
                        s.getSlug(),
                        s.getProject().getOwner().getEmail(),
                        s.getProject().getId(),
                        s.getProject().getName(),
                        s.getType(),
                        s.getStatus(),
                        s.getSuspendedReason(),
                        s.getMemoryMb()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KeyView> listKeys() {
        return keys.findAll(Sort.by("code")).stream()
                .map(k -> new KeyView(k.getCode(), k.getDescription(), k.getUnit()))
                .toList();
    }

    private User requireUser(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiNotFoundException("Không tìm thấy user"));
    }

    private AdminDtos.UserRow toRow(User user) {
        return new AdminDtos.UserRow(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getIsActive(),
                user.getSubscriptionPlan() == null ? "free" : user.getSubscriptionPlan().getCode(),
                user.getCreatedAt(),
                adminProperties.isAdmin(user.getEmail()));
    }

    private AdminDtos.OverrideView toView(UserEntitlementOverride o) {
        return new AdminDtos.OverrideView(
                o.getId(),
                o.getEntitlementKey().getCode(),
                o.getLimitValue(),
                o.getReason(),
                o.getExpiresAt());
    }
}