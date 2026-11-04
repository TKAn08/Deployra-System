package com.paas.deployra.paas;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.common.SecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Đọc/ghi biến môi trường của service. Giá trị lưu trong DB luôn ở dạng đã mã
 * hoá.
 */
@Service
public class EnvVariableService {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Cột {@code var_key} dài 255. */
    static final int MAX_KEY_LENGTH = 255;

    /**
     * Giới hạn một giá trị biến (Linux giới hạn khoảng 128 KB cho mỗi chuỗi môi
     * trường).
     */
    static final int MAX_VALUE_LENGTH = 64 * 1024;

    private final EnvVariableRepository variables;
    private final SecretCipher cipher;
    private final AppServiceRepository services;

    public EnvVariableService(EnvVariableRepository variables, SecretCipher cipher, AppServiceRepository services) {
        this.variables = variables;
        this.cipher = cipher;
        this.services = services;
    }

    public record EnvPair(String key, String value) {
    }

    /**
     * Dùng cho API: kiểm tra service thuộc về người dùng rồi tạo hoặc cập nhật một
     * biến. Khác {@link #set}, hàm này kiểm tra quyền và đầu vào, và báo lỗi bằng
     * {@link InvalidRequestException}/{@link NotFoundException}.
     *
     * @param plainValue giá trị thật (chuỗi rỗng được phép, null thì không)
     * @return true nếu là biến mới, false nếu cập nhật biến đã có
     */
    @Transactional
    public boolean setForUser(Long userId, Long serviceId, String key, String plainValue) {
        AppService service = services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("Thiếu key");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException("Tên biến quá dài (tối đa " + MAX_KEY_LENGTH + " ký tự)");
        }
        if (plainValue == null) {
            throw new InvalidRequestException("Thiếu value (cho phép chuỗi rỗng)");
        }
        if (plainValue.length() > MAX_VALUE_LENGTH) {
            throw new InvalidRequestException("Giá trị quá dài (tối đa " + MAX_VALUE_LENGTH + " ký tự)");
        }
        boolean existed = variables.findByServiceIdAndVarKey(service.getId(), key).isPresent();
        try {
            set(service, key, plainValue);
        } catch (IllegalArgumentException e) { // tên biến sai định dạng
            throw new InvalidRequestException(e.getMessage());
        }
        return !existed;
    }

    @Transactional
    public EnvVariable set(AppService service, String key, String plainValue) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Tên biến không hợp lệ: " + key);
        }
        EnvVariable variable = variables.findByServiceIdAndVarKey(service.getId(), key)
                .orElseGet(() -> {
                    EnvVariable created = new EnvVariable();
                    created.setService(service);
                    created.setVarKey(key);
                    return created;
                });
        variable.setValueEncrypted(cipher.encrypt(plainValue));
        return variables.save(variable);
    }

    @Transactional(readOnly = true)
    public java.util.Set<String> keysOf(Long serviceId) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (EnvVariable v : variables.findByServiceId(serviceId)) {
            result.add(v.getVarKey());
        }
        return result;
    }

    /** Toàn bộ biến đã giải mã, dùng khi khởi động container. Không trả ra API. */
    @Transactional(readOnly = true)
    public Map<String, String> decryptedFor(Long serviceId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (EnvVariable variable : variables.findByServiceId(serviceId)) {
            result.put(variable.getVarKey(), cipher.decrypt(variable.getValueEncrypted()));
        }
        return result;
    }

    @Transactional
    public void delete(Long serviceId, String key) {
        variables.findByServiceIdAndVarKey(serviceId, key).ifPresent(variables::delete);
    }

    @Transactional(readOnly = true)
    public Set<String> keysOfForUser(Long userId, Long serviceId) {
        services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
        return keysOf(serviceId);
    }

    @Transactional
    public void deleteForUser(Long userId, Long serviceId, String key) {
        services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(serviceId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy service"));
        delete(serviceId, key);
    }
}