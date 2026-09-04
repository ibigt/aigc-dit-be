package cn.aigc.dit.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dit.crypto")
public class CryptoProperties {
    /**
     * AES-256-GCM 密钥材料，建议至少 32 字符；会再做 SHA-256 派生。
     */
    private String secret;
}
