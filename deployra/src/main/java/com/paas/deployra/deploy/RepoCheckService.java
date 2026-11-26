package com.paas.deployra.deploy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Kiểm tra một repo Git có tồn tại và có clone được không (truy cập ẩn danh,
 * giống
 * lúc deploy). Với github.com thì chuẩn hoá URL về dạng
 * {@code https://github.com/owner/repo}
 * (nhận cả dạng có {@code .git} hoặc {@code /tree/<branch>}).
 */
@Service
public class RepoCheckService {

    public enum Status {
        /** Public và clone được. */
        PUBLIC,
        /** Public nhưng chưa có commit nào. */
        EMPTY,
        /** Không tồn tại hoặc private (không phân biệt được, xem ProbeOutcome). */
        NOT_FOUND_OR_PRIVATE,
        /** Không kết nối được tới máy chủ git. */
        UNREACHABLE,
        INVALID_URL
    }

    /**
     * @param usable        true nếu Deployra clone được repo (và branch, nếu có
     *                      hỏi)
     * @param normalizedUrl URL đã chuẩn hoá, nên lưu URL này
     * @param branch        branch đã kiểm tra (từ tham số hoặc từ URL /tree/...)
     * @param branchExists  null nếu không kiểm tra branch
     */
    public record Result(Status status, boolean usable, String normalizedUrl, String defaultBranch,
            String branch, Boolean branchExists, String message) {

        static Result invalid(String message) {
            return new Result(Status.INVALID_URL, false, null, null, null, null, message);
        }
    }

    private static final Set<String> GITHUB_HOSTS = Set.of("github.com", "www.github.com");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final GitSourceFetcher gitFetcher;

    public RepoCheckService(GitSourceFetcher gitFetcher) {
        this.gitFetcher = gitFetcher;
    }

    public Result check(String repoUrl, String branch) {
        String url;
        String effectiveBranch = branch == null || branch.isBlank() ? null : branch.trim();
        try {
            if (repoUrl == null || repoUrl.isBlank()) {
                return Result.invalid("Thiếu repo URL");
            }
            URI uri = new URI(repoUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (GITHUB_HOSTS.contains(host) && "https".equalsIgnoreCase(uri.getScheme())) {
                String[] seg = uri.getPath() == null ? new String[0] : uri.getPath().replaceAll("^/+", "").split("/");
                if (seg.length < 2) {
                    return Result.invalid("URL GitHub phải có dạng https://github.com/<owner>/<repo>");
                }
                String owner = seg[0];
                String repo = seg[1].endsWith(".git") ? seg[1].substring(0, seg[1].length() - 4) : seg[1];
                if (!OWNER.matcher(owner).matches() || !REPO.matcher(repo).matches() || repo.equals(".")
                        || repo.equals("..")) {
                    return Result.invalid("Tên owner hoặc repo không hợp lệ");
                }
                // https://github.com/o/r/tree/<branch> (chỉ khi branch không chứa dấu /)
                if (effectiveBranch == null && seg.length == 4 && seg[2].equals("tree")) {
                    effectiveBranch = seg[3];
                }
                url = "https://github.com/" + owner + "/" + repo;
            } else {
                url = repoUrl.trim();
            }
            GitSourceFetcher.RemoteProbe probe = gitFetcher.probe(url, effectiveBranch);
            return toResult(url, effectiveBranch, probe);
        } catch (URISyntaxException e) {
            return Result.invalid("Repo URL không hợp lệ");
        } catch (SourceFetchException e) {
            return Result.invalid(e.getMessage());
        }
    }

    private static Result toResult(String url, String branch, GitSourceFetcher.RemoteProbe p) {
        return switch (p.outcome()) {
            case OK -> {
                boolean branchMissing = Boolean.FALSE.equals(p.branchExists());
                yield new Result(Status.PUBLIC, !branchMissing, url, p.defaultBranch(), branch, p.branchExists(),
                        branchMissing
                                ? "Repo public nhưng không có branch '" + branch + "'"
                                : "Repo public, Deployra clone được");
            }
            case EMPTY -> new Result(Status.EMPTY, false, url, null, branch, null,
                    "Repo tồn tại nhưng chưa có commit nào");
            case NOT_FOUND_OR_PRIVATE -> new Result(Status.NOT_FOUND_OR_PRIVATE, false, url, null, branch, null,
                    "Repo không tồn tại hoặc là private (hiện Deployra chỉ clone được repo public)");
            case UNREACHABLE -> new Result(Status.UNREACHABLE, false, url, null, branch, null,
                    "Không kết nối được tới máy chủ git, thử lại sau");
        };
    }
}