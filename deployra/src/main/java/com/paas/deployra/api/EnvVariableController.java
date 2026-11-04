package com.paas.deployra.api;

import com.paas.deployra.paas.EnvVariableService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/services/{serviceId}/env}: lưu một biến môi trường (key +
 * value). Tạo mới nếu chưa có, cập nhật nếu đã có (ví dụ điền giá trị cho biến
 * được tạo sẵn từ {@code .env} lúc upload).
 *
 * <p>
 * Body: {@code {"key": "DB_PASS", "value": "abc"}}. Value được mã hoá khi lưu
 * và
 * <b>không bao giờ</b> có trong response. Trả {@code 201} nếu là biến mới,
 * {@code 200} nếu cập nhật.
 */
@RestController
@RequestMapping("/api/services/{serviceId}/env")
public class EnvVariableController {

    public record EnvRequest(String key, String value) {
    }

    public record EnvResponse(String key, boolean created) {
    }

    private final EnvVariableService envVars;
    // private final CurrentUser currentUser;

    public EnvVariableController(EnvVariableService envVars) {
        this.envVars = envVars;
    }

    @PutMapping
    public ResponseEntity<EnvResponse> setEnv(@PathVariable Long serviceId, @RequestBody EnvRequest request) {
        boolean created = envVars.setForUser(null, serviceId, request.key(), request.value());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new EnvResponse(request.key(), created));
    }
}