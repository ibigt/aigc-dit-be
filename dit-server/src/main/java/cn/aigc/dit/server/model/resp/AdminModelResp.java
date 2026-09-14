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
    private String adapterKey;
    private String provider;
    private String endpoint;
    private String category;
    private List<String> capabilities;
    @Schema(description = "是否支持上传参考音频")
    private Boolean supportsAudioReference;
    @Schema(description = "单次生成最多可携带的参考音频数；不支持时为 0")
    private Integer maxAudioReferences;
    @Schema(description = "是否允许仅上传音频、不搭配图片或视频")
    private Boolean supportsAudioOnlyReference;
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
