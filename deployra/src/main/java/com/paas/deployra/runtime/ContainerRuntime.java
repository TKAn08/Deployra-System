package com.paas.deployra.runtime;

import com.paas.deployra.paas.AppService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lớp duy nhất được phép nói chuyện với Docker. Luồng deploy chỉ gọi interface này,
 * nên sau này đổi sang ECS Fargate hay nhiều node chỉ cần viết bản cài đặt mới.
 * Hiện chưa có bản cài đặt: người phụ trách phần runtime viết (ví dụ bằng docker-java).
 */
public interface ContainerRuntime {

    /** Build image từ thư mục nguồn bằng Dockerfile. Mỗi dòng log build đưa qua {@code logLine}. Lỗi thì ném exception. */
    void buildImage(Path contextDir, String dockerfilePath, String imageTag,
                    Consumer<String> logLine, Duration timeout);

    /** Chạy container mới (giới hạn RAM/CPU theo service, gắn nhãn Traefik). Trả về id container. */
    String startContainer(AppService service, String image, Map<String, String> env);

    /** Chờ container trả lời healthcheck; false nếu quá thời gian. */
    boolean waitHealthy(String containerId, String healthcheckPath, int port, Duration timeout);

    /** Xoá các container cũ của service, giữ lại {@code keepContainerId}. Chỉ gọi sau khi container mới đã khoẻ. */
    void removeOtherContainers(AppService service, String keepContainerId);

    void stop(AppService service);

    void start(AppService service);

    /** Xoá toàn bộ container của service (volume database giữ lại trừ khi người dùng xoá hẳn). */
    void remove(AppService service);

    void removeImage(String image);

    String logs(AppService service, int tailLines);

    ContainerStats stats(AppService service);

    record ContainerStats(double cpuPercent, long memoryBytes) {
    }
}
