package com.paas.deployra.paas;

import com.paas.deployra.common.SecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Đọc/ghi biến môi trường của service. Giá trị lưu trong DB luôn ở dạng đã mã
 * hoá.
 */
@Service
public class EnvVariableService {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final EnvVariableRepository variables;
    private final SecretCipher cipher;

    public EnvVariableService(EnvVariableRepository variables, SecretCipher cipher) {
        this.variables = variables;
        this.cipher = cipher;
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
}
