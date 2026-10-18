package com.paas.deployra.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hoá AES-256-GCM cho biến môi trường của người dùng.
 * Kết quả là base64(iv || ciphertext+tag). Khoá lấy từ {@code deployra.crypto.key} (base64 của 32 byte).
 * Nếu chưa cấu hình khoá thì ứng dụng vẫn khởi động, nhưng mã hoá/giải mã sẽ báo lỗi rõ ràng.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${deployra.crypto.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalArgumentException(
                        "deployra.crypto.key phải là base64 của đúng 32 byte (tạo bằng: openssl rand -base64 32)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public String encrypt(String plain) {
        SecretKeySpec k = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hoá được giá trị", e);
        }
    }

    public String decrypt(String token) {
        SecretKeySpec k = requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(token);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("Dữ liệu mã hoá không hợp lệ");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không giải mã được (sai khoá hoặc dữ liệu bị sửa)", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Chưa cấu hình deployra.crypto.key (biến môi trường DEPLOYRA_CRYPTO_KEY)");
        }
        return key;
    }
}
