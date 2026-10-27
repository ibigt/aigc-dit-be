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
    @Schema(description = "版本 slug：base 或自由短标识；新建角色请用 base，延伸版走 /versions")
    private String stateTag;

    @Size(max = 16)
    @Schema(description = "版本展示标签；更新非基础版时可改")
    private String versionLabel;

    @Size(max = 32)
    @Schema(description = "兼容旧入参：状态名；新建时若带此字段且角色已有基础版，将创建为版本")
    private String stateName;

    @Schema(description = "延伸父版本 id")
    private Long parentAssetId;

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
