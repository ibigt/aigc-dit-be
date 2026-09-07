package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理端保存模型")
public class AdminModelSaveReq {

    @NotBlank(message = "名称不能为空")
    @Size(max = 128)
    private String name;

    @NotBlank(message = "模型标识不能为空")
    @Size(max = 128)
    private String modelKey;

    @NotBlank(message = "接入来源不能为空")
    @Size(max = 64)
    private String provider;

    @Size(max = 512)
    private String endpoint;

    @Schema(description = "明文 API Key；更新时留空表示保持原密钥")
    private String apiKey;

    @NotBlank(message = "模型类型不能为空")
    @Schema(description = "image / video / tool")
    private String category;

    @Schema(description = "能力：t2i/i2i/t2v/r2v/v2v；r2v=参考生视频（图或视频），v2v=首尾帧。旧值 i2v/frames 写入时会规范为 r2v/v2v")
    private List<String> capabilities;

    @Schema(description = "协议附加配置（如 t2iPath / t2vPath / r2vPath），厂商字段翻译在 Adapter")
    private Map<String, Object> adapterConfig;

    @Schema(description = "计价 JSON（unit / sale / cost，以及可选的 concurrentLimit、instanceType、proxy）")
    private Map<String, Object> pricing;

    private Boolean enabled;

    @Size(max = 512)
    private String description;

    @Size(max = 64)
    private String badge;

    @Size(max = 256)
    private String logo;

    private Integer concurrentLimit;
    private String instanceType;
    private String proxy;
}
