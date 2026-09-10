package cn.aigc.dit.server.drama.model.req;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "保存分场剧本（人工编辑）")
public class DramaScriptSaveReq {

    @Size(max = 200_000, message = "剧本文本过长")
    @Schema(description = "原文；不传则保持原值")
    private String sourceText;

    @Valid
    @Schema(description = "分场剧本；不传则保持原值")
    private ScriptDoc script;
}
