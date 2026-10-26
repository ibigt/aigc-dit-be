package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新建或更新漫剧资产")
public class DramaAssetSaveReq {

    @Size(max = 128)
    @Schema(description = "资产名，须能对上剧本人物/道具/场景")
    private String name;

    @Schema(description = "角色 / 场景 / 道具")
    private String type;

    @Size(max = 64)
    @Schema(description = "状态标签：base / dream / costume / wet / custom")
    private String stateTag;

    @Size(max = 32)
    @Schema(description = "状态名（资产的「本体名·状态名」后缀），如 换装 / 湿身；状态资产必填")
    private String stateName;

    @Schema(description = "完整描述符")
    private String description;

    @Schema(description = "身份不变量")
    private String identityInvariants;

    @Schema(description = "中文生图 prompt")
    private String imagePrompt;

    @Size(max = 256)
    private String voiceLock;

    private Boolean mustSplit;
    private Boolean needsHuman;
}
