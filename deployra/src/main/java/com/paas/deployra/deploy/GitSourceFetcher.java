package com.paas.deployra.deploy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lấy mã nguồn từ một repo Git công khai bằng lệnh {@code git} trên máy chủ
 * (cần cài sẵn git).
 *
 * <p>
 * Vì URL do người dùng nhập, lớp này phòng thủ nhiều lớp:
 * <ul>
 * <li>Chỉ nhận {@code https}, cổng 443, không có user:password trong URL, không
 * có query/fragment.</li>
 * <li>Phân giải tên miền và chặn mọi địa chỉ nội bộ (loopback, mạng riêng,
 * link-local như
 * 169.254.169.254 của AWS, CGNAT, IPv6 ULA...). Cũng chặn IP viết dạng số thập
 * phân/hex.</li>
 * <li>Branch và SHA kiểm tra bằng regex để không thể chèn tuỳ chọn vào lệnh
 * git.</li>
 * <li>Git chạy với {@code protocol.allow=never} + chỉ bật https, không đọc cấu
 * hình git của máy,
 * không hỏi mật khẩu, không chạy hook, không kéo submodule.</li>
 * <li>Có hạn tổng thời gian và hạn dung lượng; xong thì xoá thư mục
 * {@code .git}.</li>
 * </ul>
 *
 * <p>
 * Giới hạn còn lại: tên miền có thể được trỏ lại sang IP nội bộ giữa lúc kiểm
 * tra và lúc git kết nối
 * (DNS rebinding), và một repo https có thể chuyển hướng sang một host https
 * khác. Muốn chặn hẳn thì chạy
 * bước clone trong một container/mạng không có đường vào mạng nội bộ.
 */
@Component
public class GitSourceFetcher {

    /** Kết quả: thư mục chứa mã nguồn (đã bỏ .git) và commit SHA đầy đủ. */
    public record FetchedSource(Path directory, String commitSha) {
    }

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final int MAX_URL_LENGTH = 500;
    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(".localhost", ".local", ".internal", ".localdomain",
            ".lan", ".home.arpa");

    private final Duration timeout;
    private final long maxRepoBytes;
    private final Set<String> allowedSchemes;
    private final Function<String, InetAddress[]> resolver;

    /** Bản dùng thật: chỉ https, phân giải DNS bằng hệ thống. */
    @Autowired
    public GitSourceFetcher(
            @Value("${deployra.git.timeout:300}") long timeoutSeconds,
            @Value("${deployra.git.max-repo-bytes:104857600}") long maxRepoBytes) {
        this(
                Duration.ofSeconds(timeoutSeconds),
                maxRepoBytes,
                Set.of("https"),
                GitSourceFetcher::systemResolve);
    }

    /** Dành cho test: cho phép thêm scheme (file) và thay bộ phân giải DNS. */
    public GitSourceFetcher(Duration timeout, long maxRepoBytes, Set<String> allowedSchemes,
            Function<String, InetAddress[]> resolver) {
        this.timeout = timeout;
        this.maxRepoBytes = maxRepoBytes;
        this.allowedSchemes = allowedSchemes;
        this.resolver = resolver;
    }

    /**
     * Lấy mã nguồn vào {@code destination} (phải trống hoặc chưa tồn tại).
     *
     * @param branch branch cần lấy; null/rỗng thì dùng branch mặc định của repo (bị
     *               bỏ qua nếu có {@code sha})
     * @param sha    commit SHA đầy đủ 40 ký tự; nếu có thì lấy đúng commit này
     * @param log    nhận từng dòng output của git (có thể null)
     */
    public FetchedSource fetch(String repoUrl, String branch, String sha, Path destination, Consumer<String> log) {
        URI uri = validateRepoUrl(repoUrl);
        String safeBranch = requireBranch(branch);
        String safeSha = requireSha(sha);
        FileTree.prepareEmptyDirectory(destination);

        long deadline = System.nanoTime() + timeout.toNanos();
        String url = uri.toString();
        try {
            if (safeSha != null) {
                emit(log, "Lấy commit " + safeSha.substring(0, 7) + " từ " + displayUrl(uri));
                run(destination, deadline, log, destination, "init", "--quiet");
                run(destination, deadline, log, destination, "remote", "add", "origin", url);
                run(destination, deadline, log, destination, "fetch", "--depth", "1", "--no-tags", "--quiet",
                        "origin", safeSha);
                run(destination, deadline, log, destination, "checkout", "--quiet", "--detach", "FETCH_HEAD");
            } else {
                emit(log, "Clone " + displayUrl(uri) + (safeBranch == null ? "" : " (branch " + safeBranch + ")"));
                List<String> args = new ArrayList<>(List.of("clone", "--depth", "1", "--single-branch",
                        "--no-tags", "--quiet"));
                if (safeBranch != null) {
                    args.add("--branch");
                    args.add(safeBranch);
                }
                args.add("--");
                args.add(url);
                args.add(destination.toAbsolutePath().toString());
                run(destination.toAbsolutePath().getParent(), deadline, log, destination, args.toArray(new String[0]));
            }

            String head = run(destination, deadline, null, destination, "rev-parse", "HEAD");
            if (!head.matches("[0-9a-f]{40,64}")) {
                throw new SourceFetchException("Không đọc được commit SHA sau khi lấy mã nguồn");
            }
            if (safeSha != null && !head.equals(safeSha)) {
                throw new SourceFetchException("Commit lấy về không khớp với SHA yêu cầu");
            }

            long size = FileTree.size(destination);
            if (size > maxRepoBytes) {
                throw new SourceFetchException("Repo quá lớn: " + (size / (1024 * 1024)) + " MB, tối đa "
                        + (maxRepoBytes / (1024 * 1024)) + " MB");
            }
            FileTree.deleteRecursively(destination.resolve(".git"));
            emit(log, "Đã lấy mã nguồn, commit " + head.substring(0, 7));
            return new FetchedSource(destination, head);
        } catch (RuntimeException e) {
            FileTree.deleteContents(destination);
            throw e;
        }
    }

    // ------------------------------------------------------------------ kiểm tra
    // đầu vào

    public URI validateRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank() || repoUrl.length() > MAX_URL_LENGTH) {
            throw new SourceFetchException("Repo URL không hợp lệ");
        }
        URI uri;
        try {
            uri = new URI(repoUrl.trim());
        } catch (URISyntaxException e) {
            throw new SourceFetchException("Repo URL không hợp lệ");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new SourceFetchException("Chỉ nhận repo URL dạng https://");
        }
        if (scheme.equals("file")) {
            return uri; // chỉ tới được đây khi test cho phép scheme file
        }
        if (uri.getRawUserInfo() != null) {
            throw new SourceFetchException("Repo URL không được chứa tên đăng nhập hoặc mật khẩu");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new SourceFetchException("Repo URL không được có dấu ? hoặc #");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SourceFetchException("Repo URL thiếu tên miền hợp lệ");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new SourceFetchException("Chỉ chấp nhận cổng 443");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || BLOCKED_HOST_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            throw new SourceFetchException("Repo URL trỏ tới địa chỉ nội bộ, bị chặn");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            throw new SourceFetchException("Không phân giải được tên miền " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SourceFetchException("Không phân giải được tên miền " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new SourceFetchException("Repo URL trỏ tới địa chỉ nội bộ, bị chặn");
            }
        }
        return uri;
    }

    public String requireBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }
        String b = branch.trim();
        if (!BRANCH.matcher(b).matches() || b.contains("..") || b.endsWith("/") || b.endsWith(".lock")) {
            throw new SourceFetchException("Tên branch không hợp lệ: " + abbreviate(b));
        }
        return b;
    }

    public String requireSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }
        String s = sha.trim().toLowerCase(Locale.ROOT);
        if (!FULL_SHA.matcher(s).matches()) {
            throw new SourceFetchException("Commit SHA phải đủ 40 ký tự hex");
        }
        return s;
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            int b2 = b[2] & 0xFF;
            return b0 == 0 // 0.0.0.0/8
                    || (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64.0.0/10 CGNAT
                    || (b0 == 192 && b1 == 0 && b2 == 0) // 192.0.0.0/24
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) // 198.18.0.0/15 benchmark
                    || b0 >= 240; // 240.0.0.0/4 và broadcast
        }
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique local
            }
            boolean nat64 = (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B);
            for (int i = 4; nat64 && i < 12; i++) {
                nat64 = b[i] == 0;
            }
            if (nat64) { // 64:ff9b::/96 nhúng IPv4 ở 4 byte cuối
                try {
                    return isInternal(InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] }));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InetAddress[] systemResolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------------ chạy git

    private List<String> baseCommand() {
        String nullDevice = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "NUL"
                : "/dev/null";
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "protocol.allow=never"));
        for (String scheme : allowedSchemes) {
            cmd.add("-c");
            cmd.add("protocol." + scheme + ".allow=always");
        }
        cmd.addAll(List.of(
                "-c", "core.hooksPath=" + nullDevice,
                "-c", "credential.helper=",
                "-c", "submodule.recurse=false",
                "-c", "advice.detachedHead=false"));
        return cmd;
    }

    private void configureEnv(Map<String, String> env) {
        env.keySet().removeIf(k -> k.startsWith("GIT_"));
        String nullDevice = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "NUL"
                : "/dev/null";
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", nullDevice);
        env.put("GIT_ASKPASS", "echo");
        env.put("LC_ALL", "C");
    }

    /**
     * Chạy một lệnh git; trả về output (đã trim). Lỗi hoặc quá hạn thì ném
     * SourceFetchException.
     */
    private String run(Path workDir, long deadlineNanos, Consumer<String> log, Path destination, String... gitArgs) {
        List<String> cmd = baseCommand();
        cmd.addAll(List.of(gitArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
        configureEnv(pb.environment());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new SourceFetchException("Không chạy được lệnh git (máy chủ đã cài git chưa?)", e);
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    if (log != null) {
                        log.accept(sanitize(line, destination));
                    }
                }
            } catch (IOException ignored) {
                // tiến trình bị dừng, bỏ qua
            }
        }, "git-output");
        reader.setDaemon(true);
        reader.start();

        long remaining = deadlineNanos - System.nanoTime();
        boolean finished;
        try {
            finished = remaining > 0 && process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SourceFetchException("Việc lấy mã nguồn bị huỷ");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new SourceFetchException("Lấy mã nguồn quá " + timeout.toSeconds() + " giây nên đã dừng");
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String text;
        synchronized (output) {
            text = output.toString().trim();
        }
        if (process.exitValue() != 0) {
            throw new SourceFetchException("git " + gitArgs[0] + " thất bại: " + tail(sanitize(text, destination)));
        }
        return text;
    }

    // ------------------------------------------------------------------ tiện ích

    private static String sanitize(String text, Path destination) {
        return text.replace(destination.toAbsolutePath().toString(), "<thư mục làm việc>");
    }

    private static String tail(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 5);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static String displayUrl(URI uri) {
        return uri.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    private static void emit(Consumer<String> log, String line) {
        if (log != null) {
            log.accept(line);
        }
    }

}