package com.paas.deployra.deploy;

import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.DeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Đọc trạng thái và log deploy cho frontend (chỉ đọc, không đổi dữ liệu). */
@Service
public class DeploymentQueryService {

    /** Dòng trong danh sách lịch sử (không kèm log). */
    public record Summary(Long deploymentId,
            String status,
            String sourceType,
            String trigger,
            Integer buildSeconds,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {
    }

    /**
     * Chi tiết một deployment.
     *
     * @param finished   deploy đã ở trạng thái cuối (LIVE, FAILED, CANCELLED,
     *                   INACTIVE)
     * @param log        phần log từ {@code offset} đã gửi lên
     * @param nextOffset gửi lại làm {@code offset} ở lần hỏi sau
     * @param moreLog    còn log chưa trả hết (log quá dài, hỏi tiếp ngay)
     */
    public record Detail(Long deploymentId,
            String status,
            String sourceType,
            String trigger,
            String commitSha,
            Integer buildSeconds,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            boolean finished,
            String log,
            int nextOffset,
            boolean moreLog) {
    }

    public record LogChunk(String text, int nextOffset, boolean more) {

        /** Tối đa số ký tự trả về mỗi lần, để một request không quá nặng. */
        static final int MAX_CHARS = 200_000;

        static LogChunk of(String fullLog, int offset) {
            return of(fullLog, offset, MAX_CHARS);
        }

        static LogChunk of(String fullLog, int offset, int maxChars) {
            String log = fullLog == null ? "" : fullLog;
            int from = Math.min(Math.max(offset, 0), log.length());
            int to = (int) Math.min((long) log.length(), (long) from + maxChars);
            // không cắt đôi một cặp surrogate (emoji...)
            if (to > from && to < log.length() && Character.isHighSurrogate(log.charAt(to - 1))) {
                to--;
            }
            return new LogChunk(log.substring(from, to), to, to < log.length());
        }
    }

    private final AppServiceRepository services;
    private final DeploymentRepository deployments;

    public DeploymentQueryService(AppServiceRepository services, DeploymentRepository deployments) {
        this.services = services;
        this.deployments = deployments;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(Long userId, Long serviceId) {
        requireOwnedService(userId, serviceId);
        return deployments.findTop50ByServiceIdOrderByCreatedAtDesc(serviceId).stream()
                .map(DeploymentQueryService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public Detail get(Long userId, Long serviceId, Long deploymentId, int offset) {
        requireOwnedService(userId, serviceId);
        Deployment d = deployments.findById(deploymentId)
                .filter(x -> x.getService().getId().equals(serviceId))
                .orElseThrow(() -> new NotFoundException("Không tìm thấy deployment"));

        LogChunk chunk = LogChunk.of(d.getBuildLog(), offset);
        Deployment.Status status = d.getStatus();
        boolean finished = status.endsDeploy() || status == Deployment.Status.INACTIVE;
        return new Detail(d.getId(), status.name(), d.getSourceType().name(), d.getTrigger().name(),
                d.getCommitSha(), d.getBuildSeconds(), d.getCreatedAt(), d.getStartedAt(), d.getFinishedAt(),
                finished, chunk.text(), chunk.nextOffset(), chunk.more());
    }

    private void requireOwnedService(Long userId, Long serviceId) {
        services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
    }

    private static Summary toSummary(Deployment d) {
        return new Summary(d.getId(), d.getStatus().name(), d.getSourceType().name(), d.getTrigger().name(),
                d.getBuildSeconds(), d.getCreatedAt(), d.getStartedAt(), d.getFinishedAt());
    }
}