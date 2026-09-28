package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理端测试模型连接（不必先保存）")
public class AdminModelTestConnectionReq {

    @Schema(description = "已存模型 ID；编辑且 API Key 留空时用库内密文")
    private Long id;

    @Schema(description = "模型类型 image/video/audio/text；新建时必填")
    private String category;

    @Schema(description = "接入来源（官方API / 官方API（token plan） / ComfyUI / RunningHub / 自定义配置）")
    private String provider;

    @Schema(description = "接口地址，当前表单值")
    private String endpoint;

    @Schema(description = "明文 API Key；留空且带 id 时使用已存密钥")
    private String apiKey;

    @Schema(description = "模型标识")
    private String modelKey;

    @Schema(description = "适配协议（adapterKey）：gpt-image-2 / seedance-2.0 / minimax-h3 / agnes-text 等")
    private String adapterKey;
}
