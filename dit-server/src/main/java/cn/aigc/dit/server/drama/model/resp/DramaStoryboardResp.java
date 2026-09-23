package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "章节 + 分镜工作台")
public class DramaStoryboardResp {

    private Long episodeId;
    private Long seriesId;
    private String status;
    private Boolean canDraft;
    @Schema(description = "正在按场拆镜（刷新后仍为 true，直到完成或超时）")
    private Boolean drafting;
    @Schema(description = "本次自动拆镜开始时间")
    private OffsetDateTime draftingAt;
    @Schema(description = "最近一次拆镜失败原因；拆镜中或成功后为空")
    private String draftError;
    private Boolean textServiceAvailable;
    private Integer lockedAssetCount;
    private Integer staleShotCount;
    @Schema(description = "本集成片预览地址")
    private String filmUrl;
    @Schema(description = "成片是否因分镜视频变更而过期")
    private Boolean filmStale;
    @Schema(description = "项目全局出片方式：r2v / i2v")
    private String videoGenerateMode;
    @Schema(description = "参考图模式下是否衔接上一镜落幅")
    private Boolean videoChainPrevTail;
    @Schema(description = "正在按当前生成方式后台重编译分镜提示词")
    private Boolean promptRecompiling;
    @Schema(description = "本集当前 RUNNING 的批量出片任务 id；无则 null")
    private Long activeBatchJobId;
    private List<DramaChapterResp> chapters = new ArrayList<>();
    private List<DramaAssetResp> lockedAssets = new ArrayList<>();
}
