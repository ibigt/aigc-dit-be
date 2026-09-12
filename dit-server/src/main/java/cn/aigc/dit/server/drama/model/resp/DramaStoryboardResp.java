package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "章节 + 分镜工作台")
public class DramaStoryboardResp {

    private Long episodeId;
    private Long seriesId;
    private String status;
    private Boolean canDraft;
    private Boolean textServiceAvailable;
    private Integer lockedAssetCount;
    private Integer staleShotCount;
    @Schema(description = "本集成片预览地址")
    private String filmUrl;
    @Schema(description = "成片是否因分镜视频变更而过期")
    private Boolean filmStale;
    private List<DramaChapterResp> chapters = new ArrayList<>();
    private List<DramaAssetResp> lockedAssets = new ArrayList<>();
}
