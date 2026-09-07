package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理端模型（不含明文密钥）")
public class AdminModelResp {
    private Long id;
    private String name;
    private String modelKey;
    private String provider;
    private String endpoint;
    private String category;
    private List<String> capabilities;
    private Map<String, Object> adapterConfig;
    private Map<String, Object> pricing;
    private Boolean enabled;
    private String description;
    private String badge;
    private String logo;
    private boolean hasApiKey;
    private Integer concurrentLimit;
    private String instanceType;
    private String proxy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
