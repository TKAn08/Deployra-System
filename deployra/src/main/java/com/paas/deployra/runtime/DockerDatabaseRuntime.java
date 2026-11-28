package com.paas.deployra.runtime;

import com.paas.deployra.paas.AppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Bản cài đặt {@link DatabaseRuntime} quản lý container PostgreSQL bằng lệnh Docker CLI.
 *
 * <p>
 * <b>Khác biệt chính với App Runtime:</b>
 * <ul>
 * <li>Không dùng Traefik HTTP router (PostgreSQL dùng giao thức TCP cổng 5432).</li>
 * <li>Gắn Docker Volume cố định ({@code deployra-vol-svc-<serviceId>}) vào {@code /var/lib/postgresql/data}.</li>
 * <li>Tên container cố định: {@code deployra-db-<slug>} để làm Hostname nội bộ cho các App container cùng mạng.</li>
 * <li>Kiểm tra sức khỏe bằng lệnh {@code pg_isready} bên trong container.</li>
 * <li>Không bao giờ chạy đồng thời 2 container cùng trỏ vào 1 volume dữ liệu.</li>
 * </ul>
 */
@Component
public class DockerDatabaseRuntime implements DatabaseRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerDatabaseRuntime.class);

    static final String LABEL_MANAGED = "deployra.managed";
    static final String LABEL_SERVICE_ID = "deployra.service-id";
    static final String LABEL_SERVICE_TYPE = "deployra.service-type";

    private static final Pattern BINARY = Pattern.compile("[A-Za-z0-9_./-]{1,200}");
    private static final Pattern NETWORK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern ID_ANY = Pattern.compile("[0-9a-f]{12,64}");
    private static final Pattern ID_FULL = Pattern.compile("[0-9a-f]{64}");

    public record CommandResult(int exitCode, String output) {
    }

    public interface CommandExecutor {
        CommandResult run(List<String> command, Duration timeout)
                throws IOException, InterruptedException, TimeoutException;
    }

    private final String docker;
    private final String network;
    private final String restartPolicy;
    private final int pidsLimit;
    private final Duration commandTimeout;
    private final CommandExecutor executor;

    @Autowired
    public DockerDatabaseRuntime(
            @Value("${deployra.runtime.docker-binary:docker}") String docker,
            @Value("${deployra.runtime.network:deployra}") String network,
            @Value("${deployra.runtime.restart-policy:unless-stopped}") String restartPolicy,
            @Value("${deployra.runtime.pids-limit:256}") int pidsLimit,
            @Value("${deployra.runtime.command-timeout:30s}") Duration commandTimeout) {
        this(docker, network, restartPolicy, pidsLimit, commandTimeout, DockerDatabaseRuntime::runProcess);
    }

    DockerDatabaseRuntime(String docker, String network, String restartPolicy, int pidsLimit,
                          Duration commandTimeout, CommandExecutor executor) {
        this.docker = requireMatches(docker, BINARY, "docker-binary");
        this.network = requireMatches(network, NETWORK, "network");
        this.restartPolicy = restartPolicy;
        this.pidsLimit = pidsLimit;
        this.commandTimeout = commandTimeout;
        this.executor = executor;
    }

    @Override
    public String startPostgres(AppService service, DatabaseConfig config) {
        String slug = requireSlug(service);
        Long serviceId = service.getId();
        String containerName = containerName(slug);
        String volumeName = volumeName(serviceId);

        // 1. Đảm bảo Volume tồn tại
        ensureVolumeExists(volumeName);

        // 2. Nếu container cũ đang tồn tại thì xoá bỏ (volume vẫn còn nguyên)
        removeContainerIfExists(containerName);

        // 3. Chuẩn bị biến môi trường cho PostgreSQL
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POSTGRES_DB", config.databaseName());
        env.put("POSTGRES_USER", config.username());
        env.put("POSTGRES_PASSWORD", config.password());
        env.put("PGDATA", "/var/lib/postgresql/data/pgdata");
        if (config.extraEnv() != null) {
            env.putAll(config.extraEnv());
        }

        Path envFile = null;
        try {
            envFile = writeEnvFile(env);

            int memMb = Math.max(64, config.memoryMb());
            int cpuMillicores = Math.max(100, config.cpuMillicores());
            double cpuCores = cpuMillicores / 1000.0;

            List<String> command = new ArrayList<>(List.of(
                    docker, "run", "-d",
                    "--name", containerName,
                    "--network", network,
                    "--network-alias", slug,
                    "--restart", restartPolicy,
                    "--memory", memMb + "m",
                    "--memory-swap", memMb + "m",
                    "--cpus", String.format(Locale.ROOT, "%.3f", cpuCores),
                    "--pids-limit", String.valueOf(pidsLimit),
                    "-v", volumeName + ":/var/lib/postgresql/data",
                    "--label", LABEL_MANAGED + "=true",
                    "--label", LABEL_SERVICE_ID + "=" + serviceId,
                    "--label", LABEL_SERVICE_TYPE + "=DATABASE",
                    "--env-file", envFile.toString(),
                    config.image()
            ));

            CommandResult result = execOk("docker run (database)", command, commandTimeout);
            return lastContainerId(result.output());
        } finally {
            deleteQuietly(envFile);
        }
    }

    @Override
    public boolean waitHealthy(String containerId, String user, String database, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> checkCmd = List.of(
                docker, "exec", containerId,
                "pg_isready",
                "-U", user,
                "-d", database,
                "-h", "127.0.0.1",
                "-p", String.valueOf(port)
        );

        while (System.nanoTime() < deadline) {
            CommandResult r = exec("pg_isready", checkCmd, Duration.ofSeconds(5));
            if (r.exitCode() == 0) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void stop(AppService service) {
        String name = containerName(requireSlug(service));
        exec("docker stop", List.of(docker, "stop", "-t", "10", name), commandTimeout);
    }

    @Override
    public void start(AppService service) {
        String name = containerName(requireSlug(service));
        execOk("docker start", List.of(docker, "start", name), commandTimeout);
    }

    @Override
    public void remove(AppService service, boolean deleteVolume) {
        String slug = requireSlug(service);
        String name = containerName(slug);
        exec("docker rm", List.of(docker, "rm", "-f", name), commandTimeout);

        if (deleteVolume) {
            String volumeName = volumeName(service.getId());
            exec("docker volume rm", List.of(docker, "volume", "rm", "-f", volumeName), commandTimeout);
        }
    }

    @Override
    public String logs(AppService service, int tailLines) {
        String name = containerName(requireSlug(service));
        int lines = Math.max(1, Math.min(tailLines, 5000));
        CommandResult r = exec("docker logs", List.of(docker, "logs", "--tail", String.valueOf(lines), name), commandTimeout);
        return r.output();
    }

    @Override
    public long getVolumeSizeBytes(AppService service) {
        // Trả về dung lượng ước tính của volume
        String volumeName = volumeName(service.getId());
        CommandResult r = exec("docker volume inspect", List.of(docker, "volume", "inspect", volumeName), commandTimeout);
        return r.exitCode() == 0 ? service.getVolumeBytes() : 0L;
    }

    // ---------- Quản lý Volume & Container ----------

    private void ensureVolumeExists(String volumeName) {
        CommandResult inspect = exec("docker volume inspect", List.of(docker, "volume", "inspect", volumeName), commandTimeout);
        if (inspect.exitCode() != 0) {
            execOk("docker volume create", List.of(docker, "volume", "create", volumeName), commandTimeout);
            log.info("Đã tạo Docker Volume: {}", volumeName);
        }
    }

    private void removeContainerIfExists(String containerName) {
        exec("docker rm", List.of(docker, "rm", "-f", containerName), commandTimeout);
    }

    public static String containerName(String slug) {
        return "deployra-db-" + slug;
    }

    public static String volumeName(Long serviceId) {
        return "deployra-vol-svc-" + serviceId;
    }

    // ---------- Helpers ----------

    private CommandResult exec(String what, List<String> command, Duration timeout) {
        try {
            return executor.run(command, timeout);
        } catch (TimeoutException e) {
            log.warn("{} quá thời gian ({}s)", what, timeout.toSeconds());
            return new CommandResult(124, "Timeout");
        } catch (Exception e) {
            log.warn("{}: lỗi chạy lệnh ({})", what, e.getMessage());
            return new CommandResult(1, e.getMessage());
        }
    }

    private CommandResult execOk(String what, List<String> command, Duration timeout) {
        CommandResult r = exec(what, command, timeout);
        if (r.exitCode() != 0) {
            throw new IllegalStateException(what + " thất bại (mã " + r.exitCode() + "): " + r.output());
        }
        return r;
    }

    private static CommandResult runProcess(List<String> command, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("Hết " + timeout.toSeconds() + "s");
        }
        return new CommandResult(process.exitValue(), output.toString());
    }

    private static Path writeEnvFile(Map<String, String> env) {
        try {
            Path file;
            try {
                file = Files.createTempFile("deployra-db-env-", ".env",
                        PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException e) {
                file = Files.createTempFile("deployra-db-env-", ".env");
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : env.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(file, sb, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được file env tạm", e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    private static String requireMatches(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Giá trị " + what + " không hợp lệ: " + value);
        }
        return value;
    }

    private static String requireSlug(AppService service) {
        if (service == null || service.getSlug() == null) {
            throw new IllegalArgumentException("Service hoặc slug không hợp lệ");
        }
        return requireMatches(service.getSlug(), SLUG, "slug");
    }

    private static String lastContainerId(String output) {
        String found = null;
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (ID_FULL.matcher(t).matches() || ID_ANY.matcher(t).matches()) {
                found = t;
            }
        }
        if (found == null) {
            throw new IllegalStateException("Không tìm thấy container ID trong output: " + output);
        }
        return found;
    }
}
