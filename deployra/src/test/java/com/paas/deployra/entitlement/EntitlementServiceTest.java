package com.paas.deployra.entitlement;

import com.paas.deployra.account.SubscriptionPlanRepository;
import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.BandwidthUsageDaily;
import com.paas.deployra.paas.BandwidthUsageDailyRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static com.paas.deployra.entitlement.EntitlementCodes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chạy trên H2; dữ liệu gói/hạn mức do DataSeeder nạp khi khởi động. Mỗi test
 * tự rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntitlementServiceTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @Autowired
    EntitlementService entitlements;
    @Autowired
    UserRepository users;
    @Autowired
    SubscriptionPlanRepository plans;
    @Autowired
    EntitlementKeyRepository keys;
    @Autowired
    AddonPackageRepository addonPackages;
    @Autowired
    UserAddonRepository userAddons;
    @Autowired
    UserEntitlementOverrideRepository overrides;
    @Autowired
    ProjectRepository projects;
    @Autowired
    AppServiceRepository services;
    @Autowired
    BandwidthUsageDailyRepository bandwidth;
    @Autowired
    Clock clock;
    @Autowired
    PasswordEncoder passwordEncoder;

    private User newUser(String planCode) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@test.dev");
        user.setPassword("test-password");
        user.hashPassword(passwordEncoder);
        if (planCode != null) {
            user.setSubscriptionPlan(plans.findByCode(planCode).orElseThrow());
        }
        return users.save(user);
    }

    private void buyAddon(User user, String key, long delta, Instant start, Instant end) {
        UserAddon addon = new UserAddon();
        addon.setUser(user);
        addon.setAddonPackage(addonPackages.findFirstByEntitlementKeyCodeAndActiveTrue(key).orElseThrow());
        addon.setEntitlementKey(keys.getReferenceById(key));
        addon.setDelta(delta);
        addon.setStartsAt(start);
        addon.setExpiresAt(end);
        userAddons.save(addon);
    }

    private Project newProject(User owner) {
        Project project = new Project();
        project.setOwner(owner);
        project.setName("demo");
        return projects.save(project);
    }

    private AppService newService(Project project, String name, AppService.Status status, int memoryMb) {
        AppService service = new AppService();
        service.setProject(project);
        service.setName(name);
        service.setStatus(status);
        service.setMemoryMb(memoryMb);
        return services.save(service);
    }

    @Test
    void userWithoutPlanGetsFreeLimits() {
        User user = newUser(null);
        assertEquals(Limit.of(1), entitlements.effectiveLimit(user.getId(), MAX_PROJECTS));
        assertEquals(Limit.of(5), entitlements.effectiveLimit(user.getId(), MONTHLY_BANDWIDTH_GB));
        assertFalse(entitlements.hasFeature(user.getId(), PRIVATE_REPOS));
    }

    @Test
    void plansReplaceEachOtherInsteadOfStacking() {
        User user = newUser("starter");
        assertEquals(Limit.of(1024), entitlements.effectiveLimit(user.getId(), MAX_TOTAL_MEMORY_MB));
        assertTrue(entitlements.hasFeature(user.getId(), AUTO_DEPLOY));
    }

    @Test
    void addonsStackOnAdditiveKeysOnly() {
        User user = newUser("starter");
        Instant now = clock.instant();
        buyAddon(user, MAX_TOTAL_MEMORY_MB, 512, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(29)));
        buyAddon(user, MAX_TOTAL_MEMORY_MB, 512, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(30)));

        assertEquals(Limit.of(1024 + 512 + 512), entitlements.effectiveLimit(user.getId(), MAX_TOTAL_MEMORY_MB));
        // khoá không additive thì add-on không ảnh hưởng
        assertEquals(Limit.of(3), entitlements.effectiveLimit(user.getId(), MAX_PROJECTS));
    }

    @Test
    void expiredAddonIsIgnored() {
        User user = newUser("starter");
        Instant now = clock.instant();
        buyAddon(user, MAX_TOTAL_MEMORY_MB, 512, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(10)));
        assertEquals(Limit.of(1024), entitlements.effectiveLimit(user.getId(), MAX_TOTAL_MEMORY_MB));
    }

    @Test
    void overrideReplacesPlanValueAndNullMeansUnlimited() {
        User user = newUser("free");
        UserEntitlementOverride override = new UserEntitlementOverride();
        override.setUser(user);
        override.setEntitlementKey(keys.getReferenceById(MAX_PROJECTS));
        override.setLimitValue(20L);
        overrides.save(override);
        assertEquals(Limit.of(20), entitlements.effectiveLimit(user.getId(), MAX_PROJECTS));

        override.setLimitValue(null);
        overrides.save(override);
        assertEquals(Limit.UNLIMITED, entitlements.effectiveLimit(user.getId(), MAX_PROJECTS));
    }

    @Test
    void expiredOverrideFallsBackToPlan() {
        User user = newUser("free");
        UserEntitlementOverride override = new UserEntitlementOverride();
        override.setUser(user);
        override.setEntitlementKey(keys.getReferenceById(MAX_PROJECTS));
        override.setLimitValue(20L);
        override.setExpiresAt(clock.instant().minusSeconds(60));
        overrides.save(override);
        assertEquals(Limit.of(1), entitlements.effectiveLimit(user.getId(), MAX_PROJECTS));
    }

    @Test
    void unknownKeyIsRejected() {
        User user = newUser(null);
        assertThrows(IllegalArgumentException.class, () -> entitlements.effectiveLimit(user.getId(), "khong_ton_tai"));
    }

    @Test
    void freeUserCannotCreateSecondProject() {
        User user = newUser(null);
        entitlements.requireCanCreateProject(user.getId());
        newProject(user);
        QuotaExceededException e = assertThrows(QuotaExceededException.class,
                () -> entitlements.requireCanCreateProject(user.getId()));
        assertEquals(MAX_PROJECTS, e.getEntitlementKey());
    }

    @Test
    void ramPoolCountsOnlyRunningAndSleepingServices() {
        User user = newUser("starter"); // pool 1024 MB
        Project project = newProject(user);
        newService(project, "a", AppService.Status.RUNNING, 512);
        newService(project, "b", AppService.Status.SLEEPING, 256);
        newService(project, "c", AppService.Status.STOPPED, 512); // không chiếm RAM
        newService(project, "d", AppService.Status.IDLE, 512); // chưa chạy

        assertEquals(768, entitlements.ramAllocatedMb(user.getId()));

        AppService big = newService(project, "e", AppService.Status.STOPPED, 512);
        assertThrows(QuotaExceededException.class,
                () -> entitlements.requireCanStartService(user.getId(), big)); // 768 + 512 > 1024

        AppService small = newService(project, "f", AppService.Status.STOPPED, 256);
        entitlements.requireCanStartService(user.getId(), small); // 768 + 256 = 1024
    }

    @Test
    void ramAddonEnlargesThePoolButNotThePerServiceCeiling() {
        User user = newUser("starter"); // pool 1024, trần mỗi service 512
        Instant now = clock.instant();
        buyAddon(user, MAX_TOTAL_MEMORY_MB, 512, now.minusSeconds(60), now.plus(Duration.ofDays(30)));

        assertEquals(512, entitlements.perServiceMemoryCeilingMb(user.getId()));
        assertThrows(QuotaExceededException.class,
                () -> entitlements.requireCanCreateService(user.getId(), AppService.Type.APP, 1024));
    }

    @Test
    void perServiceCeilingNeverExceedsRealNodeRam() {
        User user = newUser("free");
        UserEntitlementOverride override = new UserEntitlementOverride();
        override.setUser(user);
        override.setEntitlementKey(keys.getReferenceById(MAX_MEMORY_MB_PER_SERVICE));
        override.setLimitValue(null); // không giới hạn
        overrides.save(override);
        assertEquals(3072, entitlements.perServiceMemoryCeilingMb(user.getId())); // = deployra.node.allocatable-mb
    }

    @Test
    void suspendedServiceCannotStart() {
        User user = newUser("pro");
        Project project = newProject(user);
        AppService service = newService(project, "s", AppService.Status.SUSPENDED, 256);
        service.setSuspendedReason(AppService.SuspendedReason.BANDWIDTH_EXCEEDED);
        assertThrows(QuotaExceededException.class, () -> entitlements.requireCanStartService(user.getId(), service));
    }

    @Test
    void bandwidthSumsThisMonthAcrossServicesIncludingDeletedOnesButNotOtherMonths() {
        User user = newUser("free"); // 5 GB
        Project project = newProject(user);
        AppService a = newService(project, "a", AppService.Status.RUNNING, 128);
        AppService deleted = newService(project, "b", AppService.Status.STOPPED, 128);
        deleted.setDeletedAt(clock.instant());

        YearMonth month = YearMonth.now(clock);
        addUsage(a, month.atDay(1), 2 * GB);
        addUsage(deleted, month.atDay(1), 2 * GB);
        addUsage(a, month.minusMonths(1).atDay(15), 100 * GB); // tháng trước không tính

        assertEquals(4 * GB, entitlements.bandwidthUsedBytes(user.getId(), month));
        assertFalse(entitlements.isBandwidthExceeded(user.getId()));

        addUsage(a, month.atDay(2), 2 * GB);
        assertTrue(entitlements.isBandwidthExceeded(user.getId())); // 6 GB >= 5 GB
    }

    @Test
    void bandwidthAddonRaisesTheCap() {
        User user = newUser("free");
        Instant now = clock.instant();
        buyAddon(user, MONTHLY_BANDWIDTH_GB, 50, now.minusSeconds(60), now.plus(Duration.ofDays(10)));
        assertEquals(Limit.of(55), entitlements.effectiveLimit(user.getId(), MONTHLY_BANDWIDTH_GB));
    }

    @Test
    void quotaReportListsUsageNextToLimits() {
        User user = newUser("starter");
        Project project = newProject(user);
        newService(project, "a", AppService.Status.RUNNING, 512);

        var report = entitlements.quotaReport(user.getId());
        QuotaLine ram = report.stream().filter(l -> l.key().equals(MAX_TOTAL_MEMORY_MB)).findFirst().orElseThrow();
        assertEquals(512, ram.used());
        assertEquals(Limit.of(1024), ram.limit());
        QuotaLine projectsLine = report.stream().filter(l -> l.key().equals(MAX_PROJECTS)).findFirst().orElseThrow();
        assertEquals(1, projectsLine.used());
    }

    private void addUsage(AppService service, LocalDate day, long bytes) {
        BandwidthUsageDaily row = new BandwidthUsageDaily();
        row.setService(service);
        row.setUsageDate(day);
        row.setBytesOut(bytes);
        bandwidth.save(row);
    }
}
