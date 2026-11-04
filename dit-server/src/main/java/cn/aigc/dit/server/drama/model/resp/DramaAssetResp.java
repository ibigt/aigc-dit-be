package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "漫剧资产（角色 / 场景 / 道具）")
public class DramaAssetResp {

    private Long id;
    private Long seriesId;
    private Long episodeId;
    private String name;
    private String type;
    private String stateTag;
    @Schema(description = "版本展示标签；基础版为「基础」")
    private String versionLabel;
    @Schema(description = "兼容旧字段：同 versionLabel（非基础）或空")
    private String stateLabel;
    @Schema(description = "延伸父版本 id")
    private Long parentAssetId;
    @Schema(description = "分镜 @ 引用名：基础版为短名，其它为「名·标签」")
    private String mentionName;
    @Schema(description = "同族版本数（含基础）")
    private Integer versionCount;
    private String description;
    private String identityInvariants;
    private String imagePrompt;
    private String voiceLock;
    private String voiceReferenceKey;
    private String voiceReferenceUrl;
    private String voiceReferenceMime;
    private Boolean mustSplit;
    private Boolean needsHuman;
    private String status;
    private Integer identityVersion;
    private String imageStorageKey;
    private Long imageAssetId;
    private String imageUrl;
    @Schema(description = "出片前图生文生图写在原图旁的 Seedream 绑定图；没有绑定则为空。只用于查看，不改变当前选用图。")
    private String seedreamBindKey;
    @Schema(description = "当前选用的生成记录 id")
    private Long selectedGenerationId;
    private Long genTaskId;
    private String genTaskStatus;
    private String genErrorMessage;
    private Boolean generating;
    @Schema(description = "已有当前选用图，即可进入分镜参考")
    private Boolean referenceEligible;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
