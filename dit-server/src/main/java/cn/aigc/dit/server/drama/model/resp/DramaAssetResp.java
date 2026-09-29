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
