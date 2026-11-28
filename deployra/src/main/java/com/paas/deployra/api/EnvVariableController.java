package com.paas.deployra.api;

import com.paas.deployra.paas.EnvVariableService;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD biến môi trường của service.
 *
 * <ul>
 * <li>{@code PUT /env} — tạo mới hoặc cập nhật 1 biến</li>
 * <li>{@code GET /env} — liệt kê tên biến (KHÔNG trả value)</li>
 * <li>{@code DELETE /env/{key}} — xoá 1 biến (idempotent)</li>
 * </ul>
 *
 * <p>
 * Value được mã hoá khi lưu và <b>không bao giờ</b> có trong response —
 * kể cả GET cũng chỉ trả về key.
 */
@RestController
@RequestMapping("/api/services/{serviceId}/env")
public class EnvVariableController {

    public record EnvRequest(String key, String value) {
    }

    public record EnvResponse(String key, boolean created) {
    }

    /** GET: chỉ tên biến, không có value. */
    public record EnvListResponse(List<String> keys) {
    }

    private final EnvVariableService envVars;

    public EnvVariableController(EnvVariableService envVars) {
        this.envVars = envVars;
    }

    @PutMapping
    public ResponseEntity<EnvResponse> setEnv(
            @PathVariable Long serviceId,
            @RequestBody EnvRequest request) {
        boolean created = envVars.setForUser(null, serviceId, request.key(), request.value());
        return ResponseEntity
                .status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new EnvResponse(request.key(), created));
    }

    @GetMapping
    public EnvListResponse listEnv(@PathVariable Long serviceId) {
        Set<String> keys = envVars.keysOfForUser(null, serviceId);
        return new EnvListResponse(List.copyOf(keys));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteEnv(
            @PathVariable Long serviceId,
            @PathVariable String key) {
        envVars.deleteForUser(null, serviceId, key);
        return ResponseEntity.noContent().build(); // 204 idempotent
    }
}