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

    @NotBlank(message = "适配协议不能为空")
    @Size(max = 64)
    @Schema(description = "适配协议：图像/视频见 AdapterCatalog；文本为 agnes-text / openai-compat")
    private String adapterKey;

    @Size(max = 64)
    @Schema(description = "接入来源（由 adapterKey 派生，可只读展示）")
    private String provider;

    @Size(max = 512)
    private String endpoint;

    @Schema(description = "明文 API Key；可空。更新时留空表示保持原密钥，新建留空表示无鉴权")
    private String apiKey;

    @NotBlank(message = "模型类型不能为空")
    @Schema(description = "image / video / text / tool")
    private String category;

    @Schema(description = "能力：t2i/i2i/t2v/r2v/i2v；r2v=参考生视频（图或视频），i2v=首尾帧。旧值 v2v/frames 写入时会规范为 i2v")
    private List<String> capabilities;

    @Schema(description = "协议附加配置（如 t2iPath / t2vPath / r2vPath / i2vPath），厂商字段翻译在 Adapter")
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
