package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "导入原文或梗概")
public class DramaScriptImportReq {

    @NotBlank(message = "请粘贴剧本或梗概")
    @Size(max = 200_000, message = "剧本文本过长")
    @Schema(description = "导入原文、小说或一句话梗概")
    private String sourceText;
}
