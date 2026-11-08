package com.paas.deployra.runtime;

import com.paas.deployra.paas.AppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bản cài đặt {@link ContainerRuntime} bằng cách gọi lệnh {@code docker} (không
 * cần
 * thư viện Docker trong Java). Deployra cần truy cập được Docker daemon: mount
 * {@code /var/run/docker.sock} hoặc đặt {@code DOCKER_HOST}.
 *
 * <p>
 * <b>Mạng và định tuyến.</b> Container người dùng được gắn vào một network
 * Docker
 * chung ({@code deployra.runtime.network}) với Traefik và với chính Deployra,
 * để
 * Traefik định tuyến theo nhãn và {@link #waitHealthy} gọi được vào container.
 * Container không publish cổng ra máy chủ. Host của service là
 * {@code <slug>.<deployra.runtime.base-domain>}.
 *
 * <p>
 * <b>Không gián đoạn khi deploy.</b> Container cũ và mới cùng nhãn Traefik nên
 * cùng
 * một service; nhãn health check của Traefik giúp nó không chuyển request vào
 * container mới trước khi container đó trả lời đúng đường dẫn health.
 *
 * <p>
 * <b>An toàn cho mã không tin cậy.</b> Mọi giá trị đưa vào lệnh hoặc nhãn đều
 * được
 * kiểm tra định dạng (không qua shell). Container bị giới hạn RAM/CPU/số tiến
 * trình,
 * bỏ toàn bộ capability và chặn leo quyền. Biến môi trường đi qua file tạm
 * quyền
 * 0600 (xoá ngay sau khi chạy) nên giá trị không xuất hiện trong danh sách tiến
 * trình.
 *
 * <p>
 * <b>Biến môi trường.</b> Biến có giá trị rỗng (ví dụ key mới nhập từ
 * {@code .env}
 * nhưng chưa điền) được coi là chưa đặt và không truyền vào container. Giá trị
 * nhiều
 * dòng chưa được hỗ trợ (file env của Docker không biểu diễn được) và bị từ
 * chối.
 * {@code PORT} mặc định bằng cổng của service, biến người dùng đặt sẽ ghi đè.
 */
@Component
public class DockerContainerRuntime implements ContainerRuntime {

    /** Nhãn đánh dấu mọi container/image do Deployra tạo. */
    static final String LABEL_MANAGED = "deployra.managed";

    /** Nhãn gắn id service để tìm lại các container của service. */
    static final String LABEL_SERVICE_ID = "deployra.service-id";

    private static final Set<String> RESTART_POLICIES = Set.of("no", "always", "unless-stopped", "on-failure");
    private static final Pattern BINARY = Pattern.compile("[A-Za-z0-9_./-]{1,200}");
    private static final Pattern NETWORK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_@.-]{1,64}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern IMAGE_TAG = Pattern
            .compile("[a-z0-9][a-z0-9._/-]{0,199}:[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");
    private static final Pattern HEALTH_PATH = Pattern
            .compile("/[A-Za-z0-9._~%/-]{0,200}(\\?[A-Za-z0-9._~%=&-]{0,200})?");
    private static final Pattern ID_FULL = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ID_ANY = Pattern.compile("[0-9a-f]{12,64}");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern INSPECT_LINE = Pattern.compile("(true|false)\\s*(\\S*)");
    private static final Pattern AMOUNT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]*)");

    /**
     * Giữ tối đa chừng này ký tự output của một lệnh (log dài hơn bị cắt, vẫn đẩy
     * hết qua lineSink).
     */
    private static final int MAX_CAPTURE_CHARS = 2 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ---------- Phần tách ra để test không cần Docker thật ----------

    /** Kết quả một lệnh: mã thoát và output (stdout + stderr gộp). */
    record CommandResult(int exitCode, String output) {
    }

    /** Chạy một lệnh và đẩy từng dòng output qua {@code lineSink} (có thể null). */
    interface CommandExecutor {
        CommandResult run(List<String> command, Duration timeout, Consumer<String> lineSink)
                throws IOException, InterruptedException, TimeoutException;
    }

    /** Gọi HTTP GET và trả mã trạng thái. */
    interface HealthProber {
        int statusOf(URI uri) throws IOException, InterruptedException;
    }

    private final String docker;
    private final String network;
    private final String baseDomain;
    private final String entrypoint;
    private final boolean tls;
    private final String certResolver;
    private final String restartPolicy;
    private final int pidsLimit;
    private final Duration commandTimeout;
    private final CommandExecutor executor;
    private final HealthProber prober;
    private final Duration pollInterval;

    @Autowired
    public DockerContainerRuntime(
            @Value("${deployra.runtime.docker-binary:docker}") String docker,
            @Value("${deployra.runtime.network:deployra}") String network,
            @Value("${deployra.runtime.base-domain:localhost}") String baseDomain,
            @Value("${deployra.runtime.entrypoint:web}") String entrypoint,
            @Value("${deployra.runtime.tls:false}") boolean tls,
            @Value("${deployra.runtime.cert-resolver:}") String certResolver,
            @Value("${deployra.runtime.restart-policy:unless-stopped}") String restartPolicy,
            @Value("${deployra.runtime.pids-limit:256}") int pidsLimit,
            @Value("${deployra.runtime.command-timeout:30s}") Duration commandTimeout) {
        this(docker, network, baseDomain, entrypoint, tls, certResolver, restartPolicy, pidsLimit, commandTimeout,
                DockerContainerRuntime::runProcess, DockerContainerRuntime::httpStatus, Duration.ofSeconds(1));
    }

    DockerContainerRuntime(String docker, String network, String baseDomain, String entrypoint, boolean tls,
            String certResolver, String restartPolicy, int pidsLimit, Duration commandTimeout,
            CommandExecutor executor, HealthProber prober, Duration pollInterval) {
        this.docker = requireMatches(docker, BINARY, "deployra.runtime.docker-binary");
        this.network = requireMatches(network, NETWORK, "deployra.runtime.network");
        this.baseDomain = requireMatches(baseDomain == null ? null : baseDomain.toLowerCase(Locale.ROOT), DOMAIN,
                "deployra.runtime.base-domain");
        this.entrypoint = requireMatches(entrypoint, WORD, "deployra.runtime.entrypoint");
        this.tls = tls;
        this.certResolver = (certResolver == null || certResolver.isBlank()) ? ""
                : requireMatches(certResolver, WORD, "deployra.runtime.cert-resolver");
        if (restartPolicy == null || !RESTART_POLICIES.contains(restartPolicy)) {
            throw new IllegalArgumentException("deployra.runtime.restart-policy phải là một trong " + RESTART_POLICIES);
        }
        this.restartPolicy = restartPolicy;
        if (pidsLimit < 16) {
            throw new IllegalArgumentException("deployra.runtime.pids-limit phải >= 16");
        }
        this.pidsLimit = pidsLimit;
        this.commandTimeout = commandTimeout;
        this.executor = executor;
        this.prober = prober;
        this.pollInterval = pollInterval;
    }

    // ---------- ContainerRuntime ----------

    @Override
    public void buildImage(Path contextDir, String dockerfilePath, String imageTag,
            Consumer<String> logLine, Duration timeout) {
        requireMatches(imageTag, IMAGE_TAG, "tên image");
        Path context = realDirectory(contextDir);
        String relative = (dockerfilePath == null || dockerfilePath.isBlank()) ? "Dockerfile" : dockerfilePath;
        Path dockerfile = resolveInside(context, relative);

        List<String> command = List.of(docker, "build", "--progress=plain",
                "-f", dockerfile.toString(),
                "-t", imageTag,
                "--label", LABEL_MANAGED + "=true",
                context.toString());
        execOk("docker build", command, timeout, logLine);
    }

    @Override
    public String startContainer(AppService service, String image, Map<String, String> env) {
        requireMatches(image, IMAGE_TAG, "tên image");
        String slug = requireSlug(service);
        int port = requirePort(service.getPort());
        String healthPath = requireHealthPath(service.getHealthcheckPath());
        int memoryMb = service.getMemoryMb();
        int cpuMillicores = service.getCpuMillicores();
        if (memoryMb < 16 || memoryMb > 262_144) {
            throw new IllegalArgumentException("memoryMb ngoài khoảng cho phép (16 đến 262144)");
        }
        if (cpuMillicores < 10 || cpuMillicores > 64_000) {
            throw new IllegalArgumentException("cpuMillicores ngoài khoảng cho phép (10 đến 64000)");
        }

        Map<String, String> effectiveEnv = effectiveEnv(port, env);
        Path envFile = null;
        try {
            envFile = writeEnvFile(effectiveEnv);

            List<String> command = new ArrayList<>(List.of(docker, "run", "-d",
                    "--name", slug + "-" + UUID.randomUUID().toString().substring(0, 8),
                    "--network", network,
                    "--restart", restartPolicy,
                    "--memory", memoryMb + "m",
                    "--memory-swap", memoryMb + "m",
                    "--cpus", String.format(Locale.ROOT, "%.3f", cpuMillicores / 1000.0),
                    "--pids-limit", String.valueOf(pidsLimit),
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges",
                    "--env-file", envFile.toString()));
            for (String label : labels(service, slug, port, healthPath)) {
                command.add("--label");
                command.add(label);
            }
            command.add(image);

            CommandResult result = execOk("docker run", command, commandTimeout, null);
            return lastContainerId(result.output());
        } finally {
            deleteQuietly(envFile);
        }
    }

    @Override
    public boolean waitHealthy(String containerId, String healthcheckPath, int port, Duration timeout) {
        requireMatches(containerId, ID_ANY, "id container");
        requireHealthPath(healthcheckPath);
        requirePort(port);

        String template = "{{.State.Running}} {{with index .NetworkSettings.Networks \"" + network
                + "\"}}{{.IPAddress}}{{end}}";
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            CommandResult inspect = exec("docker inspect",
                    List.of(docker, "inspect", "-f", template, containerId), commandTimeout, null);
            if (inspect.exitCode() != 0) {
                return false; // container không còn tồn tại
            }
            Matcher m = lastMatch(INSPECT_LINE, inspect.output());
            if (m == null || "false".equals(m.group(1))) {
                return false; // container đã dừng (thường là ứng dụng crash lúc khởi động)
            }
            String ip = m.group(2);
            if (!ip.isEmpty()) {
                try {
                    int status = prober.statusOf(URI.create("http://" + ip + ":" + port + healthcheckPath));
                    if (status >= 200 && status < 400) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    // chưa nhận kết nối: thử lại
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Override
    public void removeOtherContainers(AppService service, String keepContainerId) {
        List<String> failures = new ArrayList<>();
        for (String id : containerIds(service)) {
            if (keepContainerId != null && (id.startsWith(keepContainerId) || keepContainerId.startsWith(id))) {
                continue;
            }
            CommandResult r = exec("docker rm", List.of(docker, "rm", "-f", id), commandTimeout, null);
            if (r.exitCode() != 0) {
                failures.add(id.substring(0, 12));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Không xoá được container: " + String.join(", ", failures));
        }
    }

    @Override
    public void stop(AppService service) {
        forEachContainer(service, "docker stop", id -> List.of(docker, "stop", "-t", "10", id));
    }

    @Override
    public void start(AppService service) {
        forEachContainer(service, "docker start", id -> List.of(docker, "start", id));
    }

    @Override
    public void remove(AppService service) {
        forEachContainer(service, "docker rm", id -> List.of(docker, "rm", "-f", id));
    }

    @Override
    public void removeImage(String image) {
        requireMatches(image, IMAGE_TAG, "tên image");
        CommandResult r = exec("docker image rm", List.of(docker, "image", "rm", image), commandTimeout, null);
        if (r.exitCode() != 0 && !r.output().contains("No such image")) {
            throw new IllegalStateException("docker image rm lỗi (mã " + r.exitCode() + "): " + tail(r.output(), 5));
        }
    }

    @Override
    public String logs(AppService service, int tailLines) {
        List<String> ids = containerIds(service);
        if (ids.isEmpty()) {
            return "";
        }
        int lines = Math.max(1, Math.min(tailLines, 5000));
        // docker ps liệt kê container mới nhất trước
        return execOk("docker logs", List.of(docker, "logs", "--tail", String.valueOf(lines), ids.get(0)),
                commandTimeout, null).output();
    }

    @Override
    public ContainerStats stats(AppService service) {
        List<String> ids = containerIds(service);
        if (ids.isEmpty()) {
            return new ContainerStats(0, 0);
        }
        CommandResult r = execOk("docker stats", List.of(docker, "stats", "--no-stream", "--format",
                "{{.CPUPerc}}|{{.MemUsage}}", ids.get(0)), commandTimeout, null);
        String line = lastLine(r.output());
        String[] parts = line.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Không đọc được kết quả docker stats");
        }
        double cpu = Double.parseDouble(parts[0].trim().replace("%", ""));
        String used = parts[1].split("/", 2)[0];
        return new ContainerStats(cpu, parseBytes(used));
    }

    // ---------- Lệnh docker ----------

    private List<String> containerIds(AppService service) {
        Long id = requireServiceId(service);
        CommandResult r = execOk("docker ps",
                List.of(docker, "ps", "-a", "-q", "--no-trunc", "--filter", "label=" + LABEL_SERVICE_ID + "=" + id),
                commandTimeout, null);
        List<String> ids = new ArrayList<>();
        for (String line : r.output().split("\\R")) {
            String t = line.trim();
            if (ID_FULL.matcher(t).matches()) {
                ids.add(t);
            }
        }
        return ids;
    }

    private void forEachContainer(AppService service, String what,
            java.util.function.Function<String, List<String>> commandFor) {
        List<String> failures = new ArrayList<>();
        for (String id : containerIds(service)) {
            CommandResult r = exec(what, commandFor.apply(id), commandTimeout, null);
            if (r.exitCode() != 0) {
                failures.add(id.substring(0, 12) + ": " + tail(r.output(), 2));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(what + " lỗi: " + String.join("; ", failures));
        }
    }

    private CommandResult exec(String what, List<String> command, Duration timeout, Consumer<String> sink) {
        try {
            return executor.run(command, timeout, sink);
        } catch (TimeoutException e) {
            throw new IllegalStateException(what + " quá thời gian (" + timeout.toSeconds() + "s)");
        } catch (IOException e) {
            throw new IllegalStateException(what + ": không chạy được lệnh docker (" + messageOf(e) + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " bị ngắt");
        }
    }

    private CommandResult execOk(String what, List<String> command, Duration timeout, Consumer<String> sink) {
        CommandResult r = exec(what, command, timeout, sink);
        if (r.exitCode() != 0) {
            throw new IllegalStateException(what + " lỗi (mã " + r.exitCode() + "): " + tail(r.output(), 8));
        }
        return r;
    }

    // ---------- Nhãn Traefik ----------

    private List<String> labels(AppService service, String slug, int port, String healthPath) {
        String name = "deployra-" + slug;
        String router = "traefik.http.routers." + name;
        String svc = "traefik.http.services." + name;
        List<String> labels = new ArrayList<>();
        labels.add(LABEL_MANAGED + "=true");
        labels.add(LABEL_SERVICE_ID + "=" + service.getId());
        labels.add("traefik.enable=true");
        labels.add("traefik.docker.network=" + network);
        labels.add(router + ".rule=Host(`" + slug + "." + baseDomain + "`)");
        labels.add(router + ".entrypoints=" + entrypoint);
        labels.add(router + ".service=" + name);
        if (tls) {
            labels.add(router + ".tls=true");
            if (!certResolver.isEmpty()) {
                labels.add(router + ".tls.certresolver=" + certResolver);
            }
        }
        labels.add(svc + ".loadbalancer.server.port=" + port);
        // Traefik bỏ container chưa trả lời health ra khỏi vòng cân bằng tải
        labels.add(svc + ".loadbalancer.healthcheck.path=" + healthPath);
        labels.add(svc + ".loadbalancer.healthcheck.interval=5s");
        labels.add(svc + ".loadbalancer.healthcheck.timeout=3s");
        return labels;
    }

    // ---------- Biến môi trường ----------

    private static Map<String, String> effectiveEnv(int port, Map<String, String> env) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("PORT", String.valueOf(port));
        if (env != null) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (key == null || !ENV_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("Tên biến môi trường không hợp lệ");
                }
                if (value == null || value.isEmpty()) {
                    result.remove(key); // chưa điền giá trị: coi như chưa đặt
                    continue;
                }
                if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                    // không đưa giá trị vào thông báo lỗi
                    throw new IllegalArgumentException("Biến " + key
                            + " có giá trị nhiều dòng, chưa được hỗ trợ khi chạy container");
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private static Path writeEnvFile(Map<String, String> env) {
        try {
            Path file;
            try {
                file = Files.createTempFile("deployra-env-", ".env",
                        PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException e) { // hệ thống không phải POSIX
                file = Files.createTempFile("deployra-env-", ".env");
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : env.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(file, sb, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được file biến môi trường tạm (" + messageOf(e) + ")", e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // file nằm trong thư mục tạm, quyền 0600
        }
    }

    // ---------- Kiểm tra đầu vào ----------

    private static String requireMatches(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Giá trị không hợp lệ: " + what);
        }
        return value;
    }

    private static String requireSlug(AppService service) {
        requireServiceId(service);
        return requireMatches(service.getSlug(), SLUG, "slug");
    }

    private static Long requireServiceId(AppService service) {
        if (service == null || service.getId() == null) {
            throw new IllegalArgumentException("Service chưa có id");
        }
        return service.getId();
    }

    private static int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Cổng của service không hợp lệ");
        }
        return port;
    }

    private static String requireHealthPath(String path) {
        String p = (path == null || path.isBlank()) ? "/" : path;
        return requireMatches(p, HEALTH_PATH, "đường dẫn health check");
    }

    private static Path realDirectory(Path dir) {
        try {
            Path real = dir.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("Thư mục nguồn không tồn tại");
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("Thư mục nguồn không tồn tại");
        }
    }

    /**
     * Dockerfile phải nằm trong thư mục nguồn, kể cả khi đi qua symlink (repo git
     * có thể chứa symlink).
     */
    private static Path resolveInside(Path realContext, String relative) {
        Path candidate = realContext.resolve(relative).normalize();
        if (!candidate.startsWith(realContext)) {
            throw new IllegalArgumentException("Đường dẫn Dockerfile nằm ngoài thư mục nguồn");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Không thấy Dockerfile: " + relative);
        }
        if (!real.startsWith(realContext)) {
            throw new IllegalArgumentException("Đường dẫn Dockerfile nằm ngoài thư mục nguồn");
        }
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Dockerfile không phải file thường: " + relative);
        }
        return real;
    }

    // ---------- Phân tích output ----------

    /**
     * Output gộp cả stderr (cảnh báo của docker), nên lấy dòng cuối cùng có dạng id
     * đầy đủ.
     */
    private static String lastContainerId(String output) {
        String found = null;
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (ID_FULL.matcher(t).matches()) {
                found = t;
            }
        }
        if (found == null) {
            throw new IllegalStateException("docker run không trả về id container hợp lệ");
        }
        return found;
    }

    private static Matcher lastMatch(Pattern pattern, String output) {
        Matcher last = null;
        for (String line : output.split("\\R")) {
            Matcher m = pattern.matcher(line.trim());
            if (m.matches()) {
                last = m;
            }
        }
        return last;
    }

    private static String lastLine(String output) {
        String last = "";
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                last = line;
            }
        }
        return last;
    }

    private static String tail(String output, int maxLines) {
        Deque<String> lines = new ArrayDeque<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                lines.addLast(line.trim());
                if (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
        }
        String joined = String.join(" | ", lines);
        return joined.length() > 1000 ? joined.substring(joined.length() - 1000) : joined;
    }

    private static String messageOf(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** Đọc "12.5MiB" hoặc "256MB" của docker stats ra số byte. */
    static long parseBytes(String text) {
        Matcher m = AMOUNT.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Không đọc được dung lượng");
        }
        double value = Double.parseDouble(m.group(1));
        long unit = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "", "b" -> 1L;
            case "kb" -> 1_000L;
            case "kib" -> 1L << 10;
            case "mb" -> 1_000_000L;
            case "mib" -> 1L << 20;
            case "gb" -> 1_000_000_000L;
            case "gib" -> 1L << 30;
            case "tb" -> 1_000_000_000_000L;
            case "tib" -> 1L << 40;
            default -> throw new IllegalArgumentException("Đơn vị dung lượng lạ");
        };
        return Math.round(value * unit);
    }

    // ---------- Chạy tiến trình và HTTP thật ----------

    private static CommandResult runProcess(List<String> command, Duration timeout, Consumer<String> lineSink)
            throws IOException, InterruptedException, TimeoutException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close(); // không có stdin
        StringBuilder captured = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (captured) {
                        if (captured.length() < MAX_CAPTURE_CHARS) {
                            captured.append(line).append('\n');
                        }
                    }
                    if (lineSink != null) {
                        try {
                            lineSink.accept(line);
                        } catch (RuntimeException ignored) {
                            // lỗi ở nơi nhận log không được làm dừng lệnh
                        }
                    }
                }
            } catch (IOException ignored) {
                // tiến trình bị kết thúc
            }
        }, "docker-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                reader.join(2000);
                throw new TimeoutException("hết " + timeout.toSeconds() + "s");
            }
        } catch (InterruptedException e) {
            killTree(process);
            throw e;
        }
        reader.join(5000);
        synchronized (captured) {
            return new CommandResult(process.exitValue(), captured.toString());
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static int httpStatus(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}