package cn.aigc.dit.server.crypto;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.config.CryptoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmEncryptorTest {

    private AesGcmEncryptor encryptor;

    @BeforeEach
    void setUp() {
        CryptoProperties properties = new CryptoProperties();
        properties.setSecret("dit-test-crypto-secret-32bytes!!");
        encryptor = new AesGcmEncryptor(properties);
    }

    @Test
    void encryptDecryptRoundTrip() {
        String cipher = encryptor.encrypt("sk-live-secret");
        assertTrue(cipher.startsWith("v1:"));
        assertEquals("sk-live-secret", encryptor.decrypt(cipher));
    }

    @Test
    void samePlaintextYieldsDifferentCipher() {
        assertNotEquals(encryptor.encrypt("same"), encryptor.encrypt("same"));
    }

    @Test
    void blankPlaintextReturnsNull() {
        assertNull(encryptor.encrypt("  "));
        assertNull(encryptor.decrypt(null));
    }

    @Test
    void rejectTamperedCipher() {
        String cipher = encryptor.encrypt("secret");
        assertThrows(BizException.class, () -> encryptor.decrypt(cipher + "xx"));
    }
}
