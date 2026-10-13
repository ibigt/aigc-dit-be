package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ark.ArkAssetCredentialSource;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.entity.ArkReviewCredential;
import cn.aigc.dit.server.mapper.ArkReviewCredentialMapper;
import cn.aigc.dit.server.model.resp.ArkReviewCredentialResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class ArkReviewCredentialService implements ArkAssetCredentialSource {

    static final int ROW_ID = 1;

    private final ArkReviewCredentialMapper credentialMapper;
    private final AesGcmEncryptor encryptor;
    private final GenerateProperties generateProperties;

    public ArkReviewCredentialResp status() {
        ArkReviewCredential row = credentialMapper.selectById(ROW_ID);
        return toResp(row);
    }

    /** 库里或环境变量里两项都有时，角色页才显示审核按钮。 */
    public boolean reviewEnabled() {
        return StringUtils.hasText(accessKey()) && StringUtils.hasText(secretKey());
    }

    public ArkReviewCredentialResp save(String accessKey, String secretKey) {
        ArkReviewCredential row = credentialMapper.selectById(ROW_ID);
        boolean creating = row == null;
        if (creating) {
            row = new ArkReviewCredential();
            row.setId(ROW_ID);
        }
        if (StringUtils.hasText(accessKey)) {
            row.setAccessKeyEnc(encryptor.encrypt(accessKey.trim()));
        }
        if (StringUtils.hasText(secretKey)) {
            row.setSecretKeyEnc(encryptor.encrypt(secretKey.trim()));
        }
        if (!StringUtils.hasText(row.getAccessKeyEnc()) || !StringUtils.hasText(row.getSecretKeyEnc())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请同时填写 Access Key 和 Secret Key");
        }
        row.setUpdatedAt(OffsetDateTime.now());
        if (creating) {
            credentialMapper.insert(row);
        } else {
            credentialMapper.updateById(row);
        }
        return toResp(row);
    }

    @Override
    public String accessKey() {
        return firstConfigured(storedPlain(true), generateProperties.getArk().getAccessKey());
    }

    @Override
    public String secretKey() {
        return firstConfigured(storedPlain(false), generateProperties.getArk().getSecretKey());
    }

    private String storedPlain(boolean accessKey) {
        ArkReviewCredential row = credentialMapper.selectById(ROW_ID);
        if (row == null) {
            return null;
        }
        String encrypted = accessKey ? row.getAccessKeyEnc() : row.getSecretKeyEnc();
        if (!StringUtils.hasText(encrypted)) {
            return null;
        }
        return encryptor.decrypt(encrypted);
    }

    private static ArkReviewCredentialResp toResp(ArkReviewCredential row) {
        ArkReviewCredentialResp resp = new ArkReviewCredentialResp();
        boolean hasAccessKey = row != null && StringUtils.hasText(row.getAccessKeyEnc());
        boolean hasSecretKey = row != null && StringUtils.hasText(row.getSecretKeyEnc());
        resp.setHasAccessKey(hasAccessKey);
        resp.setHasSecretKey(hasSecretKey);
        resp.setConfigured(hasAccessKey && hasSecretKey);
        return resp;
    }

    private static String firstConfigured(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }
}
