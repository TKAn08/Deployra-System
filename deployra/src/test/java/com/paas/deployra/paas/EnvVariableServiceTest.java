package com.paas.deployra.paas;

import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.common.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra {@link EnvVariableService#setForUser} (hàm dùng cho API lưu biến). */
class EnvVariableServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SERVICE_ID = 5L;
    private static final String SECRET = "super-secret-value-123";

    private EnvVariableRepository variables;
    private SecretCipher cipher;
    private AppServiceRepository services;
    private EnvVariableService envVars;
    private AppService service;

    @BeforeEach
    void setUp() {
        variables = mock(EnvVariableRepository.class);
        cipher = mock(SecretCipher.class);
        services = mock(AppServiceRepository.class);
        envVars = new EnvVariableService(variables, cipher, services);

        service = new AppService();
        service.setId(SERVICE_ID);
        when(services.findByIdAndProjectOwnerIdAndDeletedAtIsNull(SERVICE_ID, USER_ID))
                .thenReturn(Optional.of(service));
        when(variables.findByServiceIdAndVarKey(any(), any())).thenReturn(Optional.empty());
        when(variables.save(any(EnvVariable.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cipher.encrypt(any())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
    }

    @Test
    void newVariableIsCreatedAndStoredEncrypted() {
        boolean created = envVars.setForUser(USER_ID, SERVICE_ID, "DB_PASS", SECRET);

        assertTrue(created);
        ArgumentCaptor<EnvVariable> saved = ArgumentCaptor.forClass(EnvVariable.class);
        verify(variables).save(saved.capture());
        assertEquals("DB_PASS", saved.getValue().getVarKey());
        assertEquals("enc(" + SECRET + ")", saved.getValue().getValueEncrypted());
        assertEquals(service, saved.getValue().getService());
    }

    @Test
    void existingVariableIsUpdatedInPlace() {
        EnvVariable existing = new EnvVariable();
        existing.setService(service);
        existing.setVarKey("DB_PASS");
        existing.setValueEncrypted("enc(old)");
        when(variables.findByServiceIdAndVarKey(SERVICE_ID, "DB_PASS")).thenReturn(Optional.of(existing));

        boolean created = envVars.setForUser(USER_ID, SERVICE_ID, "DB_PASS", "new");

        assertFalse(created);
        verify(variables).save(existing);
        assertEquals("enc(new)", existing.getValueEncrypted());
    }

    @Test
    void emptyValueIsAllowed() {
        assertTrue(envVars.setForUser(USER_ID, SERVICE_ID, "API_KEY", ""));
        verify(cipher).encrypt("");
    }

    @Test
    void nullValueIsRejected() {
        assertThrows(InvalidRequestException.class, () -> envVars.setForUser(USER_ID, SERVICE_ID, "A", null));
        verify(variables, never()).save(any(EnvVariable.class));
    }

    @Test
    void blankOrNullKeyIsRejected() {
        assertThrows(InvalidRequestException.class, () -> envVars.setForUser(USER_ID, SERVICE_ID, " ", "x"));
        assertThrows(InvalidRequestException.class, () -> envVars.setForUser(USER_ID, SERVICE_ID, null, "x"));
        verify(variables, never()).save(any(EnvVariable.class));
    }

    @Test
    void badKeyFormatBecomesInvalidRequestWithoutLeakingTheValue() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> envVars.setForUser(USER_ID, SERVICE_ID, "1BAD", SECRET));

        assertFalse(e.getMessage().contains(SECRET), "lỗi không được chứa value");
        verify(variables, never()).save(any(EnvVariable.class));
    }

    @Test
    void tooLongKeyOrValueIsRejected() {
        String longKey = "K".repeat(EnvVariableService.MAX_KEY_LENGTH + 1);
        String longValue = "v".repeat(EnvVariableService.MAX_VALUE_LENGTH + 1);

        assertThrows(InvalidRequestException.class, () -> envVars.setForUser(USER_ID, SERVICE_ID, longKey, "x"));
        assertThrows(InvalidRequestException.class, () -> envVars.setForUser(USER_ID, SERVICE_ID, "A", longValue));
        verify(variables, never()).save(any(EnvVariable.class));
    }

    @Test
    void serviceThatDoesNotBelongToTheUserIsNotFound() {
        assertThrows(NotFoundException.class, () -> envVars.setForUser(999L, SERVICE_ID, "A", "x"));
        verify(variables, never()).save(any(EnvVariable.class));
    }
}
