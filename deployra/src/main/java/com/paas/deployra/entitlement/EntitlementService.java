package com.paas.deployra.entitlement;

import com.paas.deployra.account.PlanEntitlement;
import com.paas.deployra.account.PlanEntitlementRepository;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.SubscriptionPlanRepository;
import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.BandwidthUsageDailyRepository;
import com.paas.deployra.paas.DeploymentRepository;
import com.paas.deployra.paas.ProjectRepository;
import com.paas.deployra.paas.SourceUploadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trung tâm tính hạn mức:
 * <pre>
 *   hạn mức hiệu lực = (override ?? giá trị của gói ?? default_limit) + tổng add-on còn hiệu lực
 * </pre>
 * Add-on chỉ cộng với khoá additive. Giá trị NULL của gói/override nghĩa là không giới hạn nên bỏ qua add-on.
 * Hạ gói không xoá gì: chỉ chặn tạo mới/khởi động khi đang vượt hạn mức.
 */
@Service
@Transactional(readOnly = true)
public class EntitlementService {

    public static final String FREE_PLAN_CODE = "free";

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    private static final List<AppService.Status> MEMORY_RESERVING =
            List.of(AppService.Status.RUNNING, AppService.Status.SLEEPING);

    private final UserRepository users;
    private final SubscriptionPlanRepository plans;
    private final EntitlementKeyRepository keys;
    private final PlanEntitlementRepository planEntitlements;
    private final UserEntitlementOverrideRepository overrides;
    private final UserAddonRepository addons;
    private final ProjectRepository projects;
    private final AppServiceRepository services;
    private final DeploymentRepository deployments;
    private final SourceUploadRepository uploads;
    private final BandwidthUsageDailyRepository bandwidth;
    private final Clock clock;
    private final long nodeAllocatableMb;

    public EntitlementService(UserRepository users,
                              SubscriptionPlanRepository plans,
                              EntitlementKeyRepository keys,
                              PlanEntitlementRepository planEntitlements,
                              UserEntitlementOverrideRepository overrides,
                              UserAddonRepository addons,
                              ProjectRepository projects,
                              AppServiceRepository services,
                              DeploymentRepository deployments,
                              SourceUploadRepository uploads,
                              BandwidthUsageDailyRepository bandwidth,
                              Clock clock,
                              @Value("${deployra.node.allocatable-mb:3072}") long nodeAllocatableMb) {
        this.users = users;
        this.plans = plans;
        this.keys = keys;
        this.planEntitlements = planEntitlements;
        this.overrides = overrides;
        this.addons = addons;
        this.projects = projects;
        this.services = services;
        this.deployments = deployments;
        this.uploads = uploads;
        this.bandwidth = bandwidth;
        this.clock = clock;
        this.nodeAllocatableMb = nodeAllocatableMb;
    }

    // ------------------------------------------------------------------ hạn mức

    public Limit effectiveLimit(Long userId, String key) {
        EntitlementKey definition = keys.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("Khoá hạn mức không tồn tại: " + key));
        Instant now = clock.instant();

        Optional<UserEntitlementOverride> override = overrides
                .findByUserIdAndEntitlementKeyCode(userId, key)
                .filter(o -> o.getExpiresAt() == null || o.getExpiresAt().isAfter(now));

        Long base; // null = không giới hạn
        if (override.isPresent()) {
            base = override.get().getLimitValue();
        } else {
            SubscriptionPlan plan = planOf(userId);
            Optional<PlanEntitlement> planValue = planEntitlements.findByPlanIdAndEntitlementKeyCode(plan.getId(), key);
            base = planValue.isPresent() ? planValue.get().getLimitValue() : Long.valueOf(definition.getDefaultLimit());
        }

        if (base == null) {
            return Limit.UNLIMITED;
        }
        long total = base;
        if (definition.isAdditive()) {
            total += addons.sumActiveDelta(userId, key, now);
        }
        return Limit.of(total);
    }

    /** Tính năng bật/tắt như private_repos, auto_deploy, always_on, custom_domains. */
    public boolean hasFeature(Long userId, String key) {
        return effectiveLimit(userId, key).enabled();
    }

    /** Trần RAM của một service = min(hạn mức theo gói, RAM thật có thể bán của node). Add-on không nâng trần này. */
    public long perServiceMemoryCeilingMb(Long userId) {
        Limit limit = effectiveLimit(userId, EntitlementCodes.MAX_MEMORY_MB_PER_SERVICE);
        return limit.unlimited() ? nodeAllocatableMb : Math.min(limit.value(), nodeAllocatableMb);
    }

    // ------------------------------------------------------------------ đã dùng

    public long usedProjects(Long userId) {
        return projects.countByOwnerIdAndDeletedAtIsNull(userId);
    }

    public long usedServices(Long userId, AppService.Type type) {
        return services.countByProjectOwnerIdAndTypeAndDeletedAtIsNull(userId, type);
    }

    /** RAM đã cấp = tổng memory_mb của service RUNNING hoặc SLEEPING. Service dừng không chiếm RAM. */
    public long ramAllocatedMb(Long userId) {
        return services.sumMemoryMb(userId, MEMORY_RESERVING);
    }

    public long bandwidthUsedBytes(Long userId, YearMonth month) {
        return bandwidth.sumBytesOut(userId, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    /** Ảnh còn giữ + zip chưa xoá + volume database. */
    public long storageUsedBytes(Long userId) {
        return deployments.sumRetainedImageBytes(userId)
                + uploads.sumSizeBytes(userId)
                + services.sumVolumeBytes(userId);
    }

    public long buildSecondsUsed(Long userId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return deployments.sumBuildSeconds(userId, from, to);
    }

    public boolean isBandwidthExceeded(Long userId) {
        Limit limit = effectiveLimit(userId, EntitlementCodes.MONTHLY_BANDWIDTH_GB);
        if (limit.unlimited()) {
            return false;
        }
        return bandwidthUsedBytes(userId, currentMonth()) >= limit.value() * GB;
    }

    // ------------------------------------------------------------------ kiểm tra trước khi làm

    public void requireCanCreateProject(Long userId) {
        require(userId, EntitlementCodes.MAX_PROJECTS, usedProjects(userId), 1,
                "Đã đạt số project tối đa của gói hiện tại");
    }

    /** Tạo service (chưa chạy nên chưa chiếm RAM trong pool, nhưng không được vượt trần RAM/service). */
    public void requireCanCreateService(Long userId, AppService.Type type, int memoryMb) {
        String countKey = type == AppService.Type.DATABASE
                ? EntitlementCodes.MAX_DATABASES : EntitlementCodes.MAX_SERVICES;
        require(userId, countKey, usedServices(userId, type), 1,
                type == AppService.Type.DATABASE
                        ? "Đã đạt số database tối đa của gói hiện tại"
                        : "Đã đạt số service tối đa của gói hiện tại");

        long ceiling = perServiceMemoryCeilingMb(userId);
        if (memoryMb > ceiling) {
            throw new QuotaExceededException(EntitlementCodes.MAX_MEMORY_MB_PER_SERVICE,
                    "RAM mỗi service tối đa " + ceiling + " MB (yêu cầu " + memoryMb + " MB)");
        }
    }

    /** Chạy hoặc khởi động lại một service: service chưa chiếm RAM thì pool RAM phải còn đủ chỗ. */
    public void requireCanStartService(Long userId, AppService service) {
        if (service.getStatus() == AppService.Status.SUSPENDED) {
            throw new QuotaExceededException(EntitlementCodes.MAX_TOTAL_MEMORY_MB,
                    "Service đang bị tạm ngưng" + (service.getSuspendedReason() == null
                            ? "" : " (" + service.getSuspendedReason() + ")"));
        }
        if (service.reservesMemory()) {
            return; // deploy lại service đang chạy không tăng RAM đã cấp
        }
        require(userId, EntitlementCodes.MAX_TOTAL_MEMORY_MB, ramAllocatedMb(userId), service.getMemoryMb(),
                "Không đủ RAM trong gói. Hãy dừng service khác, nâng gói hoặc mua thêm RAM");
    }

    public void requireCanBuild(Long userId) {
        Limit minutes = effectiveLimit(userId, EntitlementCodes.MONTHLY_BUILD_MINUTES);
        if (minutes.unlimited()) {
            return;
        }
        long usedSeconds = buildSecondsUsed(userId, currentMonth());
        if (usedSeconds >= minutes.value() * 60) {
            throw new QuotaExceededException(EntitlementCodes.MONTHLY_BUILD_MINUTES,
                    "Đã hết " + minutes.value() + " phút build của tháng này");
        }
    }

    public void requireCanUpload(Long userId, long sizeBytes) {
        Limit perFile = effectiveLimit(userId, EntitlementCodes.MAX_UPLOAD_MB);
        if (!perFile.allows(0, (sizeBytes + MB - 1) / MB)) {
            throw new QuotaExceededException(EntitlementCodes.MAX_UPLOAD_MB,
                    "File zip tối đa " + perFile.value() + " MB");
        }
        Limit storage = effectiveLimit(userId, EntitlementCodes.MAX_STORAGE_GB);
        if (!storage.unlimited() && storageUsedBytes(userId) + sizeBytes > storage.value() * GB) {
            throw new QuotaExceededException(EntitlementCodes.MAX_STORAGE_GB,
                    "Hết dung lượng lưu trữ (" + storage.value() + " GB)");
        }
    }

    // ------------------------------------------------------------------ báo cáo cho giao diện

    public List<QuotaLine> quotaReport(Long userId) {
        YearMonth month = currentMonth();
        List<QuotaLine> lines = new ArrayList<>();
        lines.add(line(userId, EntitlementCodes.MAX_PROJECTS, usedProjects(userId)));
        lines.add(line(userId, EntitlementCodes.MAX_SERVICES, usedServices(userId, AppService.Type.APP)));
        lines.add(line(userId, EntitlementCodes.MAX_DATABASES, usedServices(userId, AppService.Type.DATABASE)));
        lines.add(line(userId, EntitlementCodes.MAX_TOTAL_MEMORY_MB, ramAllocatedMb(userId)));
        lines.add(line(userId, EntitlementCodes.MAX_STORAGE_GB, ceilDiv(storageUsedBytes(userId), GB)));
        lines.add(line(userId, EntitlementCodes.MONTHLY_BANDWIDTH_GB, ceilDiv(bandwidthUsedBytes(userId, month), GB)));
        lines.add(line(userId, EntitlementCodes.MONTHLY_BUILD_MINUTES, ceilDiv(buildSecondsUsed(userId, month), 60)));
        return lines;
    }

    // ------------------------------------------------------------------ nội bộ

    private QuotaLine line(Long userId, String key, long used) {
        return new QuotaLine(key, effectiveLimit(userId, key), used);
    }

    private void require(Long userId, String key, long used, long requested, String message) {
        Limit limit = effectiveLimit(userId, key);
        if (!limit.allows(used, requested)) {
            throw new QuotaExceededException(key,
                    message + " (đã dùng " + used + ", giới hạn " + limit.value() + ")");
        }
    }

    private SubscriptionPlan planOf(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user " + userId));
        SubscriptionPlan plan = user.getSubscriptionPlan();
        if (plan != null) {
            return plan;
        }
        return plans.findByCode(FREE_PLAN_CODE)
                .orElseThrow(() -> new IllegalStateException("Thiếu gói Free (code=free) trong subscription_plans"));
    }

    private YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
