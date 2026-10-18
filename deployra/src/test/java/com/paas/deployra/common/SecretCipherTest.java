package com.paas.deployra.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretCipherTest {

    private static String newKey() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void encryptThenDecryptReturnsOriginal() {
        SecretCipher cipher = new SecretCipher(newKey());
        String token = cipher.encrypt("postgres://user:pa55@db:5432/app");
        assertNotEquals("postgres://user:pa55@db:5432/app", token);
        assertEquals("postgres://user:pa55@db:5432/app", cipher.decrypt(token));
    }

    @Test
    void sameValueEncryptsDifferentlyEachTime() {
        SecretCipher cipher = new SecretCipher(newKey());
        assertNotEquals(cipher.encrypt("abc"), cipher.encrypt("abc"));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String token = new SecretCipher(newKey()).encrypt("secret");
        SecretCipher other = new SecretCipher(newKey());
        assertThrows(IllegalStateException.class, () -> other.decrypt(token));
    }

    @Test
    void missingKeyFailsClearlyOnUse() {
        SecretCipher cipher = new SecretCipher("");
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }

    @Test
    void keyOfWrongLengthIsRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(shortKey));
    }
}
