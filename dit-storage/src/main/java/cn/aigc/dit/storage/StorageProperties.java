package cn.aigc.dit.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dit.s3")
public class StorageProperties {
    /**
     * S3 / MinIO endpoint，例如 {@code http://minio:9000}。未配置时不创建客户端。
     */
    private String endpoint = "";
    private String bucket = "dit";
    private String accessKey = "";
    private String secretKey = "";
    private String region = "us-east-1";
    private boolean pathStyle = true;
}
