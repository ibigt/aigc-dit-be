package cn.aigc.dit.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "dit.s3")
public class StorageProperties {
    /**
     * S3 / MinIO endpoint，例如 {@code http://minio:9000}。后端读写用，未配置时不创建客户端。
     */
    private String endpoint = "";
    /**
     * 签给外部服务的地址，例如 {@code https://files.example.com}。
     * 留空时签名仍用 {@link #endpoint}。角色审核要求这里是公网 HTTPS。
     */
    private String publicEndpoint = "";
    private String bucket = "dit";
    private String accessKey = "";
    private String secretKey = "";
    private String region = "us-east-1";
    private boolean pathStyle = true;

    /** 预签名使用的 endpoint。配置了公网地址时用公网，否则用内部 endpoint。 */
    public String presignEndpoint() {
        String chosen = StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint;
        if (chosen == null) {
            return "";
        }
        String value = chosen.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
