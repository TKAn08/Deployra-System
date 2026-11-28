package com.paas.deployra.api;

import com.paas.deployra.deploy.DatabaseProvisionService;
import com.paas.deployra.runtime.DatabaseRuntime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller API quản lý Database Service (PostgreSQL).
 */
@RestController
@RequestMapping("/api/services/{serviceId}/database")
public class DatabaseController {

    private final DatabaseProvisionService dbService;
    private final DatabaseRuntime runtime;
    private final com.paas.deployra.paas.AppServiceRepository services;

    public DatabaseController(DatabaseProvisionService dbService,
                              DatabaseRuntime runtime,
                              com.paas.deployra.paas.AppServiceRepository services) {
        this.dbService = dbService;
        this.runtime = runtime;
        this.services = services;
    }

    /**
     * Khởi tạo & chạy database (tạo volume, chạy container postgres, trả về credentials).
     */
    @PostMapping("/provision")
    public ResponseEntity<DatabaseProvisionService.DatabaseConnectionInfo> provision(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        DatabaseProvisionService.DatabaseConnectionInfo info = dbService.provision(userId, serviceId);
        return ResponseEntity.ok(info);
    }

    /**
     * Dừng database container.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        dbService.stop(userId, serviceId);
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    /**
     * Khởi động lại database container đã dừng.
     */
    @PostMapping("/start")
    public ResponseEntity<DatabaseProvisionService.DatabaseConnectionInfo> start(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        DatabaseProvisionService.DatabaseConnectionInfo info = dbService.start(userId, serviceId);
        return ResponseEntity.ok(info);
    }

    /**
     * Xem thông tin kết nối và thông tin đăng nhập database.
     */
    @GetMapping("/info")
    public ResponseEntity<DatabaseProvisionService.DatabaseConnectionInfo> getInfo(
            @PathVariable Long serviceId,
            @RequestParam(required = false) Long userId) {
        DatabaseProvisionService.DatabaseConnectionInfo info = dbService.getConnectionInfo(userId, serviceId);
        return ResponseEntity.ok(info);
    }

    /**
     * Liên kết Database này với một APP service cùng project:
     * Tự động truyền biến DATABASE_URL vào biến môi trường của APP service đó.
     */
    @PostMapping("/link/{targetAppId}")
    public ResponseEntity<Map<String, Object>> linkToApp(
            @PathVariable Long serviceId,
            @PathVariable Long targetAppId,
            @RequestParam(required = false) Long userId) {
        boolean linked = dbService.linkToApp(userId, serviceId, targetAppId);
        return ResponseEntity.ok(Map.of(
                "linked", linked,
                "databaseServiceId", serviceId,
                "targetAppServiceId", targetAppId,
                "message", "Đã truyền biến DATABASE_URL vào service đích thành công"
        ));
    }

    /**
     * Xem logs của database container.
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, String>> logs(
            @PathVariable Long serviceId,
            @RequestParam(defaultValue = "100") int lines,
            @RequestParam(required = false) Long userId) {
        var service = (userId == null)
                ? services.findById(serviceId).filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new com.paas.deployra.common.NotFoundException("Không tìm thấy service"))
                : services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new com.paas.deployra.common.NotFoundException("Không tìm thấy service"));

        String logOutput = runtime.logs(service, lines);
        return ResponseEntity.ok(Map.of("logs", logOutput));
    }
}
