package cn.aigc.dit.server.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dit.jwt")
public class JwtProperties {
    /**
     * HS256 密钥，至少 32 字节。
     */
    private String secret;
    private long expireSeconds = 86400L;
}
