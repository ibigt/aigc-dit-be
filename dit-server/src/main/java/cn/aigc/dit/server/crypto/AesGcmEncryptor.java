package cn.aigc.dit.server.crypto;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.config.CryptoProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密，用于模型 api_key 落库。密文格式：{@code v1:<base64(iv+ciphertext+tag)>}。
 * 不以 {@code v1:} 开头的值按明文回传，便于服务器手工把 Key 写入 {@code api_key_enc}。
 */
@Component
public class AesGcmEncryptor {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmEncryptor(CryptoProperties properties) {
        this.key = new SecretKeySpec(sha256(properties.getSecret()), "AES");
    }

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new BizException(ErrorCode.INTERNAL, "密钥加密失败");
        }
    }

    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            return null;
        }
        String value = ciphertext.trim();
        if (!value.startsWith(PREFIX)) {
            // 允许运维直接把明文 Key 写入 api_key_enc；管理端保存后会改成 v1: 密文
            return value;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (packed.length <= IV_LENGTH) {
                throw new BizException(ErrorCode.INTERNAL, "密钥密文格式无效");
            }
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new BizException(ErrorCode.INTERNAL, "密钥解密失败");
        }
    }

    private static byte[] sha256(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("dit.crypto.secret must not be blank");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
