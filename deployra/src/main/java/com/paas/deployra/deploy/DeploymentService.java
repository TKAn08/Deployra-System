package com.paas.deployra.deploy;

import com.paas.deployra.entitlement.EntitlementService;
import com.paas.deployra.paas.*;
import com.paas.deployra.paas.Deployment.Status;
import com.paas.deployra.paas.Deployment.Trigger;
import com.paas.deployra.runtime.ContainerRuntime;
import com.paas.deployra.storage.SourceStorage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Service
public class DeploymentService implements DeploymentServiceInterface {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    private static final String DOCKERFILE = "Dockerfile";

    private final DeploymentRepository deployments;
    private final AppServiceRepository services;
    private final GitSourceFetcher gitFetcher;
    private final ZipSourceExtractor zipExtractor;
    private final SourceStorage storage;
    private final ContainerRuntime runtime;
    private final EnvVariableService envVars;
    private final EnvFileImporter envFileImporter;
    private final EntitlementService entitlements;
    private final Clock clock;
    private final DeploymentProperties props;
    private final TransactionTemplate tx;

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Long, ReentrantLock> serviceLocks = new ConcurrentHashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean stopped;

    public DeploymentService(DeploymentRepository deployments,
            AppServiceRepository services,
            GitSourceFetcher gitFetcher,
            ZipSourceExtractor zipExtractor,
            SourceStorage storage,
            ContainerRuntime runtime,
            EnvVariableService envVars,
            EnvFileImporter envFileImporter,
            EntitlementService entitlements,
            Clock clock,
            DeploymentProperties props,
            TransactionTemplate tx) {
        this.deployments = deployments;
        this.services = services;
        this.gitFetcher = gitFetcher;
        this.zipExtractor = zipExtractor;
        this.storage = storage;
        this.runtime = runtime;
        this.envVars = envVars;
        this.envFileImporter = envFileImporter;
        this.entitlements = entitlements;
        this.clock = clock;
        this.props = props;
        this.tx = tx;
    }

    // ---------- Vòng đời ----------

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        recoverOnStartup();
        int n = props.getWorkerCount();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(this::workerLoop, "deploy-worker-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        log.info("DeploymentService khởi động: {} worker", n);
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        workers.forEach(Thread::interrupt);
    }

    private void recoverOnStartup() {
        tx.executeWithoutResult(s -> {
            for (Deployment d : deployments.findByStatusIn(List.of(Status.BUILDING, Status.DEPLOYING))) {
                d.setStatus(Status.FAILED);
                d.setFinishedAt(clock.instant());
                d.setBuildLog(appendLine(d.getBuildLog(), "FAILED: Ứng dụng khởi động lại"));
                deployments.save(d);
            }
            for (Deployment d : deployments.findByStatusIn(List.of(Status.QUEUED))) {
                queue.offer(d.getId());
            }
        });
    }

    // ---------- API công khai (1a) ----------

    @Override
    public Deployment enqueue(AppService service, Trigger trigger) {
        return doEnqueue(service, trigger, null);
    }

    @Override
    public Deployment enqueue(AppService service, Trigger trigger, SourceUpload upload) {
        Objects.requireNonNull(upload, "upload bắt buộc cho deploy ZIP");
        return doEnqueue(service, trigger, upload);
    }

    private Deployment doEnqueue(AppService service, Trigger trigger, SourceUpload upload) {
        Deployment saved = tx.execute(s -> {
            // Huỷ deploy QUEUED cũ nếu có
            deployments.findFirstByServiceIdAndStatusOrderByCreatedAtDesc(service.getId(), Status.QUEUED)
                    .ifPresent(old -> {
                        old.transitionTo(Status.CANCELLED, clock.instant());
                        old.setBuildLog(appendLine(old.getBuildLog(), "CANCELLED: bị huỷ bởi deploy mới"));
                        deployments.save(old);
                    });
            Deployment d = new Deployment();
            d.setService(service);
            d.setTrigger(trigger);
            d.setStatus(Status.QUEUED);
            d.setUpload(upload);
            d.setSourceType(upload != null ? Deployment.SourceType.ZIP : Deployment.SourceType.GIT); // thêm dòng này
            return deployments.save(d);
        });
        queue.offer(saved.getId());
        return saved;
    }

    // ---------- Worker ----------

    private void workerLoop() {
        while (!stopped) {
            try {
                Long id = queue.poll(500, TimeUnit.MILLISECONDS);
                if (id != null)
                    runOne(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.error("Worker lỗi", t);
            }
        }
    }

    private void runOne(Long id) {
        Deployment peek = tx.execute(s -> deployments.findById(id).orElse(null));
        if (peek == null || peek.getStatus() != Status.QUEUED)
            return;

        Long serviceId = peek.getService().getId();
        ReentrantLock lock = serviceLocks.computeIfAbsent(serviceId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            queue.offer(id);
            sleepQuietly(200);
            return;
        }
        try {
            Deployment started = tryBeginBuilding(id);
            if (started != null)
                pipeline(started);
        } finally {
            lock.unlock();
        }
    }

    private void moveTo(Deployment d, Status next) {
        d.transitionTo(next, clock.instant());
        save(d);
    }

    private Deployment tryBeginBuilding(Long id) {
        return tx.execute(s -> {
            Deployment d = deployments.findById(id).orElse(null);
            if (d == null || d.getStatus() != Status.QUEUED)
                return null;
            d.transitionTo(Status.BUILDING, clock.instant());
            d.setStartedAt(clock.instant());
            return deployments.save(d);
        });
    }

    // ---------- Pipeline (1c, 1d, 1e) ----------

    private void pipeline(Deployment d) {
        Path workDir = null;
        try {
            AppService svc = d.getService();
            Long userId = svc.getProject().getOwner().getId();

            // 1e: hạn mức build
            try {
                entitlements.requireCanBuild(userId);
            } catch (RuntimeException e) {
                fail(d, "Vượt gói: " + msg(e));
                return;
            }

            // 1c: tải mã nguồn
            workDir = Files.createTempDirectory("paas-deploy-" + d.getId() + "-");
            Consumer<String> logSink = logSink(d);
            Path sourceDir = fetchSource(d, workDir, logSink);
            saveIfChanged(d);

            if (!Files.isRegularFile(sourceDir.resolve(DOCKERFILE))) {
                fail(d, "Thiếu Dockerfile ở gốc mã nguồn");
                return;
            }

            // Import .env/.env.example (có thể tạo biến mới → phải làm trước khi build)
            try {
                envFileImporter.importInto(svc, sourceDir, logSink);
            } catch (RuntimeException e) {
                log.warn("Import .env lỗi cho deploy {}: {}", d.getId(), msg(e));
            }

            // 1d: build
            String imageTag = imageTag(d);
            appendLog(d, "Bắt đầu build: " + imageTag);
            long t0 = System.nanoTime();
            try {
                runtime.buildImage(sourceDir, DOCKERFILE, imageTag, logSink, props.getBuildTimeout());
            } catch (RuntimeException e) {
                fail(d, "Build thất bại: " + msg(e));
                return;
            }
            int secs = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);
            d.setImage(imageTag);
            d.setImagePruned(false);
            d.setImageBytes(0L); // ContainerRuntime chưa có API lấy size — xem ghi chú
            d.setBuildSeconds(secs);
            save(d);

            moveTo(d, Status.DEPLOYING);

            // 1e: hạn mức khởi chạy (pool RAM)
            try {
                entitlements.requireCanStartService(userId, svc);
            } catch (RuntimeException e) {
                fail(d, "Vượt gói: " + msg(e));
                return;
            }

            Map<String, String> env = envVars.decryptedFor(svc.getId());
            String containerId;
            try {
                containerId = runtime.startContainer(svc, imageTag, env);
            } catch (RuntimeException e) {
                fail(d, "Không khởi chạy được container: " + msg(e));
                return;
            }

            boolean healthy;
            try {
                healthy = runtime.waitHealthy(containerId, props.getHealthPath(),
                        props.getAppPort(), props.getHealthTimeout());
            } catch (RuntimeException e) {
                fail(d, "Health check lỗi: " + msg(e));
                return;
            }
            if (!healthy) {
                fail(d, "Health check quá thời gian ("
                        + props.getHealthTimeout().toSeconds() + "s)");
                return;
            }

            // Zero-downtime: chỉ xoá container cũ sau khi container mới đã khoẻ
            try {
                runtime.removeOtherContainers(svc, containerId);
            } catch (RuntimeException e) {
                log.warn("Không xoá được container cũ của service {}: {}", svc.getId(), msg(e));
            }

            activate(d, svc); // d → LIVE, deploy LIVE cũ → INACTIVE, services.status → RUNNING
            appendLog(d, "Deploy thành công");
        } catch (IOException e) {
            log.error("Lỗi I/O trong pipeline deploy {}", d.getId(), e);
            safeFail(d, "Lỗi đọc/ghi file: " + msg(e));
        } catch (RuntimeException e) {
            log.error("Pipeline lỗi cho deploy {}", d.getId(), e);
            safeFail(d, "Lỗi: " + msg(e));
        } finally {
            cleanup(workDir);
        }
    }

    private Path fetchSource(Deployment d, Path workDir, Consumer<String> logSink) {
        SourceUpload upload = d.getUpload();
        if (upload != null) { // ZIP
            try (InputStream in = storage.open(upload.getObjectKey())) {
                return zipExtractor.extract(in, workDir).directory();
            } catch (RuntimeException e) {
                throw new SourceFetchException("Không đọc/giải nén được file zip: " + msg(e), e);
            } catch (Exception e) {
                throw new SourceFetchException("Không đọc được file zip: " + msg(e), e);
            }
        }
        // GIT
        AppService svc = d.getService();
        try {
            GitSourceFetcher.FetchedSource src = gitFetcher.fetch(
                    svc.getRepoUrl(), svc.getBranch(), null, workDir, logSink);
            d.setCommitSha(src.commitSha());
            return src.directory();
        } catch (RuntimeException e) {
            throw new SourceFetchException("Không tải được mã nguồn từ git: " + msg(e), e);
        }
    }

    private String imageTag(Deployment d) {
        String sha7;
        if (d.getCommitSha() != null) {
            sha7 = d.getCommitSha().substring(0, Math.min(7, d.getCommitSha().length()));
        } else if (d.getUpload() != null && d.getUpload().getSha256() != null) {
            sha7 = d.getUpload().getSha256().substring(0, 7);
        } else {
            sha7 = "local";
        }
        return "paas/" + d.getService().getSlug() + ":" + sha7 + "-" + d.getId();
    }

    /**
     * d → LIVE; deploy LIVE cũ cùng service → INACTIVE; service.status → RUNNING.
     */
    private void activate(Deployment d, AppService svc) {
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            deployments.findFirstByServiceIdAndStatusOrderByCreatedAtDesc(svc.getId(), Status.LIVE)
                    .filter(old -> !old.getId().equals(d.getId()))
                    .ifPresent(old -> {
                        old.transitionTo(Status.INACTIVE, now);
                        deployments.save(old);
                    });
            d.transitionTo(Status.LIVE, now);
            deployments.save(d);
            svc.setStatus(AppService.Status.RUNNING);
            services.save(svc);
        });
    }

    private void fail(Deployment d, String reason) {
        d.setBuildLog(appendLine(d.getBuildLog(), "FAILED: " + reason));
        d.transitionTo(Status.FAILED, clock.instant());
        save(d);
    }

    private void safeFail(Deployment d, String reason) {
        try {
            fail(d, reason);
        } catch (RuntimeException e) {
            log.warn("Không ghi FAILED", e);
        }
    }

    // ---------- Ghi DB / log ----------

    private void save(Deployment d) {
        tx.executeWithoutResult(s -> deployments.save(d));
    }

    private void saveIfChanged(Deployment d) {
        // commitSha có thể vừa set trong fetchSource
        save(d);
    }

    private void appendLog(Deployment d, String line) {
        synchronized (d) {
            d.setBuildLog(appendLine(d.getBuildLog(), line));
        }
        save(d);
    }

    private Consumer<String> logSink(Deployment d) {
        AtomicLong lastSaveAt = new AtomicLong(System.currentTimeMillis());
        AtomicInteger pending = new AtomicInteger(0);
        return line -> {
            if (line == null || line.isBlank())
                return;
            synchronized (d) {
                d.setBuildLog(appendLine(d.getBuildLog(), line));
                int count = pending.incrementAndGet();
                long now = System.currentTimeMillis();
                if (count >= 20 || now - lastSaveAt.get() >= 500) {
                    save(d);
                    lastSaveAt.set(now);
                    pending.set(0);
                }
            }
        };
    }

    private static String appendLine(String cur, String line) {
        return (cur == null || cur.isEmpty()) ? line : cur + "\n" + line;
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static void cleanup(Path dir) {
        if (dir == null)
            return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}