package com.paas.deployra.runtime;

import com.paas.deployra.paas.AppService;

import java.time.Duration;
import java.util.Map;

/**
 * Hợp đồng runtime dành riêng cho Database Service (PostgreSQL, MySQL, Redis...).
 * Khác với App service (stateless, HTTP/Traefik), Database cần Persistent Volume,
 * kết nối TCP nội bộ, và không gắn Traefik HTTP routing.
 */
public interface DatabaseRuntime {

    record DatabaseConfig(
            String image,
            String databaseName,
            String username,
            String password,
            int port,
            int memoryMb,
            int cpuMillicores,
            Map<String, String> extraEnv
    ) {
    }

    /**
     * Khởi chạy container PostgreSQL gắn với Docker Volume tương ứng.
     *
     * @param service service loại DATABASE
     * @param config  thông tin cấu hình và credentials
     * @return container ID
     */
    String startPostgres(AppService service, DatabaseConfig config);

    /**
     * Chờ PostgreSQL sẵn sàng nhận kết nối bằng pg_isready.
     */
    boolean waitHealthy(String containerId, String user, String database, int port, Duration timeout);

    /**
     * Dừng container database.
     */
    void stop(AppService service);

    /**
     * Khởi động lại container database đã dừng.
     */
    void start(AppService service);

    /**
     * Xoá container database.
     *
     * @param deleteVolume nếu true thì xoá cả Docker Volume (mất toàn bộ dữ liệu).
     */
    void remove(AppService service, boolean deleteVolume);

    /**
     * Lấy log gần nhất của container database.
     */
    String logs(AppService service, int tailLines);

    /**
     * Lấy dung lượng ổ đĩa (volume) hiện tại tính theo byte.
     */
    long getVolumeSizeBytes(AppService service);
}
