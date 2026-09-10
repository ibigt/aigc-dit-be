package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理端测试文本模型连接（不必先保存）")
public class AdminModelTestConnectionReq {

    @Schema(description = "已存模型 ID；编辑且 API Key 留空时用库内密文")
    private Long id;

    @Schema(description = "接口地址，当前表单值")
    private String endpoint;

    @Schema(description = "明文 API Key；留空且带 id 时使用已存密钥")
    private String apiKey;

    @Schema(description = "对话模型标识")
    private String modelKey;

    @Schema(description = "agnes-text / openai-compat")
    private String adapterKey;
}
