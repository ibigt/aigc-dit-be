package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.server.config.CryptoProperties;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.entity.ArkReviewCredential;
import cn.aigc.dit.server.mapper.ArkReviewCredentialMapper;
import cn.aigc.dit.server.model.resp.ArkReviewCredentialResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArkReviewCredentialServiceTest {

    @Mock
    private ArkReviewCredentialMapper mapper;

    private AesGcmEncryptor encryptor;
    private GenerateProperties properties;
    private ArkReviewCredentialService service;
    private final AtomicReference<ArkReviewCredential> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        CryptoProperties crypto = new CryptoProperties();
        crypto.setSecret("dit-dev-jwt-secret-change-me-32b");
        encryptor = new AesGcmEncryptor(crypto);
        properties = new GenerateProperties();
        service = new ArkReviewCredentialService(mapper, encryptor, properties);
        when(mapper.selectById(ArkReviewCredentialService.ROW_ID)).thenAnswer(invocation -> stored.get());
        lenient().when(mapper.insert(any(ArkReviewCredential.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void saveEncryptsBothKeysAndStatusHidesPlaintext() {
        ArkReviewCredentialResp status = service.save("AKLT-access", "secret-value");

        ArkReviewCredential row = stored.get();
        assertTrue(row.getAccessKeyEnc().startsWith("v1:"));
        assertTrue(row.getSecretKeyEnc().startsWith("v1:"));
        assertFalse(row.getAccessKeyEnc().contains("AKLT-access"));
        assertFalse(row.getSecretKeyEnc().contains("secret-value"));
        assertTrue(status.isConfigured());
        assertTrue(status.isHasAccessKey());
        assertTrue(status.isHasSecretKey());
        assertEquals("AKLT-access", service.accessKey());
        assertEquals("secret-value", service.secretKey());
    }

    @Test
    void blankFieldKeepsExistingCiphertext() {
        service.save("AKLT-access", "secret-value");
        String accessCipher = stored.get().getAccessKeyEnc();
        when(mapper.updateById(any(ArkReviewCredential.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });

        service.save("  ", "secret-next");

        assertEquals(accessCipher, stored.get().getAccessKeyEnc());
        assertEquals("AKLT-access", service.accessKey());
        assertEquals("secret-next", service.secretKey());
        verify(mapper, times(1)).insert(any(ArkReviewCredential.class));
        verify(mapper, times(1)).updateById(any(ArkReviewCredential.class));
    }

    @Test
    void firstSaveRequiresBothKeys() {
        BizException error = assertThrows(BizException.class, () -> service.save("AKLT-access", ""));

        assertEquals("请同时填写 Access Key 和 Secret Key", error.getMessage());
        assertNull(stored.get());
        verify(mapper, times(0)).insert(any(ArkReviewCredential.class));
    }

    @Test
    void storedKeyOverridesEnvironment() {
        properties.getArk().setAccessKey("ENV-AK");
        properties.getArk().setSecretKey("ENV-SK");
        service.save("AKLT-access", "secret-value");

        assertEquals("AKLT-access", service.accessKey());
        assertEquals("secret-value", service.secretKey());
    }

    @Test
    void environmentIsUsedWhenNothingIsStored() {
        properties.getArk().setAccessKey(" ENV-AK ");
        properties.getArk().setSecretKey("ENV-SK");

        assertEquals("ENV-AK", service.accessKey());
        assertEquals("ENV-SK", service.secretKey());
        assertFalse(service.status().isConfigured());
        assertTrue(service.reviewEnabled());
    }

    @Test
    void reviewStaysHiddenUntilBothKeysExist() {
        assertFalse(service.reviewEnabled());

        properties.getArk().setAccessKey("ENV-AK");
        assertFalse(service.reviewEnabled());

        properties.getArk().setSecretKey("ENV-SK");
        assertTrue(service.reviewEnabled());
    }
}
