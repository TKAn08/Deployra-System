package com.paas.deployra.seed;

import com.paas.deployra.account.Payment;
import com.paas.deployra.account.PaymentRepository;
import com.paas.deployra.account.SubscriptionPlan;
import com.paas.deployra.account.SubscriptionPlanRepository;
import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import com.paas.deployra.account.UserSubscription;
import com.paas.deployra.account.UserSubscriptionRepository;
import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Project;
import com.paas.deployra.paas.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.paas.deployra.paas.AppService.Status.IDLE;
import static com.paas.deployra.paas.AppService.Status.RUNNING;
import static com.paas.deployra.paas.AppService.Status.SLEEPING;
import static com.paas.deployra.paas.AppService.Status.STOPPED;
import static com.paas.deployra.paas.AppService.SuspendedReason.ABUSE;
import static com.paas.deployra.paas.AppService.SuspendedReason.MEMORY_OVER_QUOTA;
import static com.paas.deployra.paas.AppService.SuspendedReason.PLAN_EXPIRED;

/**
 * Dữ liệu mẫu cho user, project và service, CHỈ để dev/demo.
 * Mặc định tắt; bật bằng {@code SEED_SAMPLE_DATA=true} (hoặc
 * {@code deployra.seed.sample-data=true}).
 * Chạy sau {@link DataSeeder} vì cần các gói free/starter/pro đã có.
 * Chạy lặp lại an toàn: user trùng email thì dùng lại, project trùng tên của
 * cùng user thì bỏ qua.
 *
 * Mật khẩu chung của mọi tài khoản mẫu: {@value #SAMPLE_PASSWORD}.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "deployra.seed.sample-data", havingValue = "true")
public class SampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    static final String SAMPLE_PASSWORD = "Password@123";

    /**
     * planCode null = Free. expiredPlanCode != null = từng mua gói đó nhưng đã hết
     * hạn.
     */
    private record UserDef(String email,
            String firstName,
            String lastName,
            String planCode,
            String expiredPlanCode,
            boolean active,
            String googleId,
            Integer lastLoginDaysAgo,
            List<ProjectDef> projects) {
    }

    private static final long GB = 1024L * 1024 * 1024;

    private record ProjectDef(String name, boolean deleted, List<ServiceDef> services) {
        static ProjectDef active(String name, ServiceDef... services) {
            return new ProjectDef(name, false, List.of(services));
        }

        static ProjectDef deleted(String name, ServiceDef... services) {
            return new ProjectDef(name, true, List.of(services));
        }
    }

    /**
     * Mô tả một service mẫu; các hàm with/idleHours/... trả về bản sao đã chỉnh.
     */
    private record ServiceDef(String name,
            AppService.Type type,
            AppService.Status status,
            int memoryMb,
            int port,
            String repoUrl,
            Integer lastActiveHoursAgo,
            AppService.SuspendedReason suspendedReason,
            long volumeBytes,
            boolean deleted) {

        static ServiceDef app(String name, AppService.Status status, int memoryMb) {
            return new ServiceDef(name, AppService.Type.APP, status, memoryMb, 8000,
                    null, null, null, 0, false);
        }

        static ServiceDef database(String name, AppService.Status status, int memoryMb, long volumeGb) {
            return new ServiceDef(name, AppService.Type.DATABASE, status, memoryMb, 5432,
                    null, null, null, volumeGb * GB, false);
        }

        ServiceDef withPort(int newPort) {
            return new ServiceDef(name, type, status, memoryMb, newPort, repoUrl, lastActiveHoursAgo,
                    suspendedReason, volumeBytes, deleted);
        }

        ServiceDef withRepo(String url) {
            return new ServiceDef(name, type, status, memoryMb, port, url, lastActiveHoursAgo,
                    suspendedReason, volumeBytes, deleted);
        }

        ServiceDef idleHours(int hours) {
            return new ServiceDef(name, type, status, memoryMb, port, repoUrl, hours,
                    suspendedReason, volumeBytes, deleted);
        }

        /** Đặt luôn status = SUSPENDED kèm lý do. */
        ServiceDef suspended(AppService.SuspendedReason reason) {
            return new ServiceDef(name, type, AppService.Status.SUSPENDED, memoryMb, port, repoUrl,
                    lastActiveHoursAgo, reason, volumeBytes, deleted);
        }

        ServiceDef softDeleted() {
            return new ServiceDef(name, type, status, memoryMb, port, repoUrl, lastActiveHoursAgo,
                    suspendedReason, volumeBytes, true);
        }
    }

    private static final List<UserDef> USERS = List.of(
            // Free, chưa từng đăng nhập, chưa có project: kiểm tra trạng thái "trống".
            new UserDef("free.new@example.com", "An", "Nguyễn Văn",
                    null, null, true, null, null, List.of()),

            // Free, hoạt động hôm qua, đúng 1 project (đã chạm hạn mức Free).
            new UserDef("free.active@example.com", "Châu", "Lê Minh",
                    null, null, true, null, 1,
                    // service IDLE chưa deploy: dùng để thử upload zip / deploy thật.
                    List.of(ProjectDef.active("portfolio-site",
                            ServiceDef.app("portfolio-site", IDLE, 256)))),

            // Free, 10 ngày chưa đăng nhập: dùng để thử job tự dừng app Free sau 7 ngày.
            new UserDef("free.idle@example.com", "Dũng", "Phạm Quốc",
                    null, null, true, null, 10,
                    // RUNNING 256MB (đầy pool Free), 48 giờ không có request: thử job auto-sleep.
                    List.of(ProjectDef.active("todo-api",
                            ServiceDef.app("todo-api", RUNNING, 256).idleHours(48)))),

            // Đăng nhập bằng Google (mật khẩu chỉ là giá trị ngẫu nhiên, không dùng được).
            new UserDef("free.google@example.com", "Hà", "Hoàng Thu",
                    null, null, true, "google-seed-0001", 2,
                    // IDLE, đã gắn repo public có Dockerfile ở gốc (app lắng nghe cổng 3000).
                    List.of(ProjectDef.active("blog-nextjs",
                            ServiceDef.app("blog-web", IDLE, 256)
                                    .withPort(3000)
                                    .withRepo("https://github.com/docker/welcome-to-docker")))),

            // Tài khoản bị khoá.
            new UserDef("free.disabled@example.com", "Khoa", "Bùi Anh",
                    null, null, false, null, 40,
                    // Bị đình chỉ vì vi phạm.
                    List.of(ProjectDef.active("legacy-app",
                            ServiceDef.app("legacy-app", STOPPED, 256).suspended(ABUSE)))),

            // Starter, đúng 3 project (chạm hạn mức Starter).
            new UserDef("starter.user@example.com", "Ngọc", "Trần Bảo",
                    "starter", null, true, null, 0,
                    // 2 app + 1 database, RAM 256 + 512 + 256 = 1024MB: đúng bằng pool Starter.
                    List.of(ProjectDef.active("shop-frontend",
                            ServiceDef.app("shop-frontend", RUNNING, 256)),
                            ProjectDef.active("shop-backend",
                                    ServiceDef.app("shop-backend", RUNNING, 512)),
                            ProjectDef.active("shop-admin",
                                    ServiceDef.database("shop-admin-db", RUNNING, 256, 1)))),

            // Pro, 5 project đang dùng + 1 project đã xoá mềm (không tính vào hạn mức).
            new UserDef("pro.user@example.com", "Long", "Vũ Hoàng",
                    "pro", null, true, null, 0,
                    // 5 app (đúng hạn mức) + 1 database. RAM đang giữ: 256 + 512 + 512 + 256 =
                    // 1536MB / 2048MB;
                    // ml-inference (1024MB) đã dừng nên không chiếm pool.
                    List.of(ProjectDef.active("saas-landing",
                            ServiceDef.app("saas-landing", RUNNING, 256)),
                            ProjectDef.active("saas-api",
                                    ServiceDef.app("saas-api", RUNNING, 512),
                                    ServiceDef.database("saas-db", RUNNING, 512, 2)),
                            ProjectDef.active("saas-worker",
                                    ServiceDef.app("saas-worker", SLEEPING, 256).idleHours(3)),
                            ProjectDef.active("ml-inference",
                                    ServiceDef.app("ml-inference", STOPPED, 1024)),
                            ProjectDef.active("internal-dashboard",
                                    ServiceDef.app("internal-dashboard", IDLE, 256)),
                            ProjectDef.deleted("old-prototype",
                                    ServiceDef.app("old-prototype", STOPPED, 256).softDeleted()))),

            // Từng là Pro, nay đã về Free nhưng vẫn giữ 3 project: kiểm tra chính sách
            // "hạ gói không xoá dữ liệu" (vượt hạn mức thì không cho tạo mới, vẫn cho
            // xem/xoá).
            new UserDef("downgraded@example.com", "Tâm", "Đặng Thanh",
                    null, "pro", true, null, 20,
                    // Free chỉ cho 1 service / 256MB, nhưng user còn 3 service: 1 vẫn chạy, 2 bị
                    // đình chỉ.
                    List.of(ProjectDef.active("crm-web",
                            ServiceDef.app("crm-web", RUNNING, 256)),
                            ProjectDef.active("crm-api",
                                    ServiceDef.app("crm-api", STOPPED, 512).suspended(MEMORY_OVER_QUOTA)),
                            ProjectDef.active("crm-reports",
                                    ServiceDef.app("crm-reports", STOPPED, 256).suspended(PLAN_EXPIRED)))));

    private final UserRepository users;
    private final ProjectRepository projects;
    private final AppServiceRepository services;
    private final SubscriptionPlanRepository plans;
    private final UserSubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SampleDataSeeder(UserRepository users,
            ProjectRepository projects,
            AppServiceRepository services,
            SubscriptionPlanRepository plans,
            UserSubscriptionRepository subscriptions,
            PaymentRepository payments,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.projects = projects;
        this.services = services;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int newUsers = 0;
        int newProjects = 0;
        int newServices = 0;
        for (UserDef def : USERS) {
            User user = users.findByEmail(def.email()).orElse(null);
            if (user == null) {
                user = createUser(def);
                newUsers++;
            }
            int[] created = seedProjects(user, def.projects());
            newProjects += created[0];
            newServices += created[1];
        }
        log.info("Sample data: thêm {} user, {} project, {} service (mật khẩu chung: {})",
                newUsers, newProjects, newServices, SAMPLE_PASSWORD);
    }

    private User createUser(UserDef def) {
        Instant now = Instant.now(clock);

        User user = new User();
        user.setEmail(def.email());
        user.setFirstName(def.firstName());
        user.setLastName(def.lastName());
        user.setGoogleId(def.googleId());
        // Tài khoản Google không có mật khẩu thật nhưng cột password là NOT NULL.
        user.setPassword(passwordEncoder.encode(def.googleId() != null
                ? UUID.randomUUID().toString()
                : SAMPLE_PASSWORD));
        if (def.lastLoginDaysAgo() != null) {
            user.setLastLogin(now.minus(Duration.ofDays(def.lastLoginDaysAgo())));
        }
        if (def.planCode() != null) {
            user.setSubscriptionPlan(requirePlan(def.planCode()));
        }
        user = users.save(user);

        // @PrePersist luôn đặt isActive = true, nên muốn khoá phải sửa sau khi lưu.
        if (!def.active()) {
            user.setIsActive(false);
            user = users.save(user);
        }

        if (def.expiredPlanCode() != null) {
            seedSubscription(user, requirePlan(def.expiredPlanCode()), UserSubscription.Status.EXPIRED,
                    now.minus(Duration.ofDays(65)), now.minus(Duration.ofDays(35)), "old");
        }
        if (def.planCode() != null) {
            seedSubscription(user, requirePlan(def.planCode()), UserSubscription.Status.ACTIVE,
                    now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(25)), "current");
        }
        return user;
    }

    /** Mỗi đăng ký có một Payment SUCCEEDED đi kèm, giống luồng mua gói thật. */
    private void seedSubscription(User user, SubscriptionPlan plan, UserSubscription.Status status,
            Instant start, Instant end, String tag) {
        String txnId = "seed-" + user.getEmail() + "-" + plan.getCode() + "-" + tag;
        Payment payment = payments.findByProviderTxnId(txnId).orElseGet(() -> {
            Payment created = new Payment();
            created.setUser(user);
            created.setPlan(plan);
            created.setPurpose(Payment.Purpose.PLAN);
            created.setAmount(plan.getPrice());
            created.setStatus(Payment.Status.SUCCEEDED);
            created.setProvider("mock");
            created.setProviderTxnId(txnId);
            return payments.save(created);
        });

        UserSubscription subscription = new UserSubscription();
        subscription.setUser(user);
        subscription.setPlan(plan);
        subscription.setStatus(status);
        subscription.setCurrentPeriodStart(start);
        subscription.setCurrentPeriodEnd(end);
        subscription.setPayment(payment);
        subscriptions.save(subscription);
    }

    /** @return {số project mới, số service mới} */
    private int[] seedProjects(User user, List<ProjectDef> defs) {
        if (defs.isEmpty()) {
            return new int[] { 0, 0 };
        }
        // Gồm cả project đã xoá mềm để chạy lại không tạo trùng.
        Map<String, Project> existing = projects.findAll().stream()
                .filter(p -> p.getOwner().getId().equals(user.getId()))
                .collect(Collectors.toMap(Project::getName, p -> p, (a, b) -> a));

        Instant now = Instant.now(clock);
        int newProjects = 0;
        int newServices = 0;
        for (ProjectDef def : defs) {
            Project project = existing.get(def.name());
            if (project == null) {
                project = new Project();
                project.setOwner(user);
                project.setName(def.name());
                if (def.deleted()) {
                    project.setDeletedAt(now.minus(Duration.ofDays(3)));
                }
                project = projects.save(project);
                newProjects++;
            }
            newServices += seedServices(project, def.services(), now);
        }
        return new int[] { newProjects, newServices };
    }

    private int seedServices(Project project, List<ServiceDef> defs, Instant now) {
        if (defs.isEmpty()) {
            return 0;
        }
        // Gồm cả service đã xoá mềm để chạy lại không tạo trùng.
        Set<String> existing = services.findAll().stream()
                .filter(s -> s.getProject().getId().equals(project.getId()))
                .map(AppService::getName)
                .collect(Collectors.toSet());

        int created = 0;
        for (ServiceDef def : defs) {
            if (existing.contains(def.name())) {
                continue;
            }
            createService(project, def, now);
            created++;
        }
        return created;
    }

    private void createService(Project project, ServiceDef def, Instant now) {
        AppService service = new AppService();
        service.setProject(project);
        service.setName(def.name()); // slug được @PrePersist tự sinh
        service.setType(def.type());
        service.setStatus(def.status());
        service.setMemoryMb(def.memoryMb());
        service.setPort(def.port());
        if (def.type() == AppService.Type.DATABASE) {
            // Database không có mã nguồn / health check HTTP, chỉ có volume.
            service.setBranch(null);
            service.setRootDir(null);
            service.setDockerfilePath(null);
            service.setHealthcheckPath(null);
            service.setVolumeBytes(def.volumeBytes());
        } else {
            service.setRepoUrl(def.repoUrl());
        }
        if (def.lastActiveHoursAgo() != null) {
            service.setLastActiveAt(now.minus(Duration.ofHours(def.lastActiveHoursAgo())));
        } else if (service.reservesMemory()) {
            service.setLastActiveAt(now.minus(Duration.ofMinutes(5)));
        }
        if (def.suspendedReason() != null) {
            service.setSuspendedAt(now.minus(Duration.ofDays(1)));
            service.setSuspendedReason(def.suspendedReason());
        }
        if (def.deleted()) {
            service.setDeletedAt(now.minus(Duration.ofDays(3)));
        }
        services.save(service);
    }

    private SubscriptionPlan requirePlan(String code) {
        return plans.findByCode(code).orElseThrow(() -> new IllegalStateException(
                "Chưa có gói '" + code + "'. DataSeeder phải chạy trước SampleDataSeeder."));
    }
}