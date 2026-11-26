package com.paas.deployra.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phần kiểm tra URL/branch/SHA chạy thuần Java (DNS giả). Phần clone thật dùng
 * repo git tạo tạm trên đĩa
 * qua scheme file:// (chỉ bật trong test) và tự bỏ qua nếu máy chưa cài git.
 */
class GitSourceFetcherTest {

    private static final InetAddress PUBLIC_IP = ip(93, 184, 216, 34);

    private static InetAddress ip(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Fetcher với DNS giả: host trong bảng trả về IP tương ứng, host khác trả về IP
     * công cộng.
     */
    private GitSourceFetcher fetcherWithDns(Map<String, InetAddress> table) {
        return new GitSourceFetcher(Duration.ofSeconds(30), 50L * 1024 * 1024, Set.of("https"),
                host -> new InetAddress[] { table.getOrDefault(host, PUBLIC_IP) });
    }

    private GitSourceFetcher publicFetcher() {
        return fetcherWithDns(Map.of());
    }

    private void assertRejected(GitSourceFetcher fetcher, String url) {
        assertThrows(SourceFetchException.class, () -> fetcher.validateRepoUrl(url), "phải chặn: " + url);
    }

    // ------------------------------------------------------------------ URL

    @Test
    void acceptsPlainHttpsRepoUrls() {
        GitSourceFetcher f = publicFetcher();
        assertNotNull(f.validateRepoUrl("https://github.com/owner/repo"));
        assertNotNull(f.validateRepoUrl("https://github.com/owner/repo.git"));
        assertNotNull(f.validateRepoUrl("https://gitlab.com:443/group/sub/repo.git"));
    }

    @Test
    void rejectsOtherProtocols() {
        GitSourceFetcher f = publicFetcher();
        assertRejected(f, "http://github.com/owner/repo");
        assertRejected(f, "git://github.com/owner/repo");
        assertRejected(f, "ssh://git@github.com/owner/repo");
        assertRejected(f, "file:///etc");
        assertRejected(f, "ext::sh -c 'touch /tmp/pwned'");
        assertRejected(f, "git@github.com:owner/repo.git");
        assertRejected(f, "/home/user/repo");
    }

    @Test
    void rejectsCredentialsQueryPortAndEmptyInput() {
        GitSourceFetcher f = publicFetcher();
        assertRejected(f, "https://user:pass@github.com/owner/repo");
        assertRejected(f, "https://token@github.com/owner/repo");
        assertRejected(f, "https://github.com/owner/repo?x=1");
        assertRejected(f, "https://github.com/owner/repo#frag");
        assertRejected(f, "https://github.com:8443/owner/repo");
        assertRejected(f, "");
        assertRejected(f, "   ");
        assertRejected(f, null);
        assertRejected(f, "https://" + "a".repeat(600) + ".com/x");
    }

    @Test
    void rejectsInternalAddressesWrittenAsIpOrName() {
        GitSourceFetcher f = new GitSourceFetcher(Duration.ofSeconds(30), 1024, Set.of("https"),
                host -> {
                    try {
                        return InetAddress.getAllByName(host); // chỉ dùng cho IP literal, không cần mạng
                    } catch (Exception e) {
                        throw new IllegalArgumentException(e);
                    }
                });
        assertRejected(f, "https://127.0.0.1/repo");
        assertRejected(f, "https://10.0.0.5/repo");
        assertRejected(f, "https://172.16.0.1/repo");
        assertRejected(f, "https://192.168.1.10/repo");
        assertRejected(f, "https://169.254.169.254/latest/meta-data"); // metadata của AWS
        assertRejected(f, "https://100.64.0.1/repo"); // CGNAT
        assertRejected(f, "https://0.0.0.0/repo");
        assertRejected(f, "https://[::1]/repo");
        assertRejected(f, "https://[fd00::1]/repo"); // IPv6 ULA
        assertRejected(f, "https://[::ffff:127.0.0.1]/repo"); // IPv4 nhúng trong IPv6
        assertRejected(f, "https://2130706433/repo"); // 127.0.0.1 viết dạng số
        assertRejected(f, "https://localhost/repo");
        assertRejected(f, "https://app.localhost/repo");
        assertRejected(f, "https://db.internal/repo");
        assertRejected(f, "https://printer.local/repo");
    }

    @Test
    void rejectsDomainThatResolvesToInternalAddress() {
        GitSourceFetcher f = fetcherWithDns(Map.of(
                "evil.example.com", ip(10, 1, 2, 3),
                "meta.example.com", ip(169, 254, 169, 254)));
        assertRejected(f, "https://evil.example.com/repo");
        assertRejected(f, "https://meta.example.com/repo");
        assertNotNull(f.validateRepoUrl("https://good.example.com/repo"));
    }

    @Test
    void rejectsDomainThatDoesNotResolve() {
        GitSourceFetcher f = new GitSourceFetcher(Duration.ofSeconds(30), 1024, Set.of("https"),
                host -> {
                    throw new IllegalArgumentException("unknown host");
                });
        assertRejected(f, "https://khong-ton-tai.example.com/repo");
    }

    @Test
    void ipClassification() throws Exception {
        assertTrue(GitSourceFetcher.isInternal(ip(127, 0, 0, 1)));
        assertTrue(GitSourceFetcher.isInternal(ip(192, 168, 0, 1)));
        assertTrue(GitSourceFetcher.isInternal(ip(100, 127, 255, 255)));
        assertFalse(GitSourceFetcher.isInternal(ip(100, 128, 0, 1)));
        assertFalse(GitSourceFetcher.isInternal(ip(8, 8, 8, 8)));
        assertFalse(GitSourceFetcher.isInternal(PUBLIC_IP));
        assertTrue(GitSourceFetcher.isInternal(InetAddress.getByName("64:ff9b::7f00:1"))); // NAT64 của 127.0.0.1
        assertFalse(GitSourceFetcher.isInternal(InetAddress.getByName("2606:4700:4700::1111")));
    }

    // ------------------------------------------------------------------ branch và
    // SHA

    @Test
    void branchValidation() {
        GitSourceFetcher f = publicFetcher();
        assertEquals("main", f.requireBranch("main"));
        assertEquals("feature/login-v2", f.requireBranch(" feature/login-v2 "));
        assertEquals("release-1.2.3", f.requireBranch("release-1.2.3"));
        assertEquals(null, f.requireBranch(null));
        assertEquals(null, f.requireBranch("  "));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("--upload-pack=touch /tmp/x"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("-main"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("a..b"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("a b"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("a;rm -rf /"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("x.lock"));
        assertThrows(SourceFetchException.class, () -> f.requireBranch("a/"));
    }

    @Test
    void shaValidation() {
        GitSourceFetcher f = publicFetcher();
        String sha = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(sha, f.requireSha(sha));
        assertEquals(sha, f.requireSha(sha.toUpperCase()));
        assertEquals(null, f.requireSha(null));
        assertThrows(SourceFetchException.class, () -> f.requireSha("abc1234")); // chưa đủ 40 ký tự
        assertThrows(SourceFetchException.class, () -> f.requireSha("--help" + sha.substring(6)));
        assertThrows(SourceFetchException.class, () -> f.requireSha("zz" + sha.substring(2)));
    }

    @Test
    void fetchRejectsBadInputBeforeTouchingDisk(@TempDir Path tmp) {
        GitSourceFetcher f = publicFetcher();
        Path dest = tmp.resolve("src");
        assertThrows(SourceFetchException.class, () -> f.fetch("http://github.com/a/b", "main", null, dest, null));
        assertThrows(SourceFetchException.class, () -> f.fetch("https://github.com/a/b", "--evil", null, dest, null));
        assertFalse(Files.exists(dest), "không được tạo thư mục khi đầu vào sai");
    }

    // ------------------------------------------------------------------ clone thật
    // từ repo cục bộ

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " lỗi: " + out);
        }
        return out;
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private GitSourceFetcher localFetcher(long maxBytes) {
        return new GitSourceFetcher(Duration.ofSeconds(60), maxBytes, Set.of("https", "file"),
                host -> new InetAddress[] { PUBLIC_IP });
    }

    /**
     * Tạo repo nguồn: 2 commit, branch main và branch dev. Trả về SHA của commit
     * đầu tiên.
     */
    private String createSourceRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        git(repo, "init", "--quiet");
        Files.writeString(repo.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(repo.resolve("version.txt"), "v1\n");
        git(repo, "add", "-A");
        git(repo, "commit", "--quiet", "-m", "first");
        String first = git(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("version.txt"), "v2\n");
        git(repo, "commit", "--quiet", "-am", "second");
        git(repo, "branch", "dev");
        Files.writeString(repo.resolve("only-on-main.txt"), "main\n");
        git(repo, "add", "-A");
        git(repo, "commit", "--quiet", "-m", "third");
        return first;
    }

    @Test
    void clonesBranchAndRemovesDotGit(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        String mainHead = git(repo, "rev-parse", "main");

        Path dest = tmp.resolve("work");
        List<String> lines = new ArrayList<>();
        GitSourceFetcher.FetchedSource result = localFetcher(50L * 1024 * 1024).fetch(repo.toUri().toString(), "main",
                null, dest, lines::add);

        assertEquals(mainHead, result.commitSha());
        assertEquals(dest, result.directory());
        assertEquals("v2\n", Files.readString(dest.resolve("version.txt")));
        assertTrue(Files.exists(dest.resolve("Dockerfile")));
        assertTrue(Files.exists(dest.resolve("only-on-main.txt")));
        assertFalse(Files.exists(dest.resolve(".git")), "phải xoá .git");
        assertFalse(lines.isEmpty(), "phải có log");
        assertFalse(lines.stream().anyMatch(l -> l.contains(dest.toString())), "log không lộ thư mục làm việc");
    }

    @Test
    void clonesOtherBranch(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        String devHead = git(repo, "rev-parse", "dev");

        Path dest = tmp.resolve("work");
        GitSourceFetcher.FetchedSource result = localFetcher(50L * 1024 * 1024).fetch(repo.toUri().toString(), "dev",
                null, dest, null);

        assertEquals(devHead, result.commitSha());
        assertFalse(Files.exists(dest.resolve("only-on-main.txt")), "branch dev chưa có file này");
    }

    @Test
    void clonesDefaultBranchWhenBranchIsBlank(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        Path dest = tmp.resolve("work");
        GitSourceFetcher.FetchedSource result = localFetcher(50L * 1024 * 1024).fetch(repo.toUri().toString(), null,
                null, dest, null);
        assertEquals(git(repo, "rev-parse", "HEAD"), result.commitSha());
    }

    @Test
    void fetchesExactCommitBySha(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        String first = createSourceRepo(repo);

        Path dest = tmp.resolve("work");
        GitSourceFetcher.FetchedSource result = localFetcher(50L * 1024 * 1024).fetch(repo.toUri().toString(), "main",
                first, dest, null);

        assertEquals(first, result.commitSha());
        assertEquals("v1\n", Files.readString(dest.resolve("version.txt")));
        assertFalse(Files.exists(dest.resolve(".git")));
    }

    @Test
    void missingBranchFailsAndLeavesDestinationEmpty(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        Path dest = tmp.resolve("work");

        SourceFetchException e = assertThrows(SourceFetchException.class,
                () -> localFetcher(50L * 1024 * 1024).fetch(repo.toUri().toString(), "khong-co", null, dest, null));
        assertTrue(e.getMessage().contains("git clone"), e.getMessage());
        assertFalse(e.getMessage().contains(dest.toString()), "thông báo lỗi không lộ thư mục làm việc");
        try (var entries = Files.list(dest)) {
            assertFalse(entries.findAny().isPresent(), "lỗi thì dọn sạch thư mục đích");
        }
    }

    @Test
    void unknownShaFails(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        Path dest = tmp.resolve("work");
        assertThrows(SourceFetchException.class, () -> localFetcher(50L * 1024 * 1024)
                .fetch(repo.toUri().toString(), null, "0".repeat(40), dest, null));
    }

    @Test
    void oversizedRepoIsRejectedAndCleaned(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        Files.write(repo.resolve("big.bin"), new byte[300_000]);
        git(repo, "add", "-A");
        git(repo, "commit", "--quiet", "-m", "big");

        Path dest = tmp.resolve("work");
        SourceFetchException e = assertThrows(SourceFetchException.class,
                () -> localFetcher(100_000).fetch(repo.toUri().toString(), "main", null, dest, null));
        assertTrue(e.getMessage().contains("quá lớn"), e.getMessage());
        try (var entries = Files.list(dest)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    @Test
    void refusesNonEmptyDestination(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("work");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("old.txt"), "x");
        assertThrows(IllegalArgumentException.class,
                () -> publicFetcher().fetch("https://github.com/a/b", "main", null, dest, null));
        assertTrue(Files.exists(dest.resolve("old.txt")), "không được xoá dữ liệu có sẵn");
    }

    @Test
    void timeoutStopsGit(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "máy chưa cài git");
        Path repo = tmp.resolve("origin");
        createSourceRepo(repo);
        GitSourceFetcher f = new GitSourceFetcher(Duration.ofNanos(1), 50L * 1024 * 1024,
                Set.of("https", "file"), host -> new InetAddress[] { PUBLIC_IP });
        SourceFetchException e = assertThrows(SourceFetchException.class,
                () -> f.fetch(repo.toUri().toString(), "main", null, tmp.resolve("work"), null));
        assertTrue(e.getMessage().contains("quá"), e.getMessage());
    }
}