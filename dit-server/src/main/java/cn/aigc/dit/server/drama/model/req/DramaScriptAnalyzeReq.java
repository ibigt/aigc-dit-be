package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "剧本分析配置")
public class DramaScriptAnalyzeReq {

    @NotBlank(message = "请粘贴剧本或梗概")
    @Size(max = 200_000, message = "剧本文本过长")
    private String sourceText;
}
