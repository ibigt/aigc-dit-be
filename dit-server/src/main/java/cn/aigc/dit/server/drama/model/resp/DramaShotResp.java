package cn.aigc.dit.server.drama.model.resp;

import cn.aigc.dit.server.drama.DramaShotBeats;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "漫剧分镜")
public class DramaShotResp {

    private Long id;
    private Long seriesId;
    private Long episodeId;
    private Long chapterId;
    private Integer chapterIndex;
    private String chapterName;
    private Integer shotIndex;
    private String title;
    private Integer durationSec;
    private String narrativeGoal;
    @Schema(description = "景别与运镜")
    private String cameraMotion;
    @Schema(description = "画内环境声")
    private String ambianceAudio;
    @Schema(description = "分镜内部镜头序列；空则页面仍展示 narrativeGoal")
    private DramaShotBeats shotBeats;
    private String scene;
    private String dialogue;
    private String continuityIn;
    private String continuityOut;
    private List<Long> referenceAssetIds = new ArrayList<>();
    private List<DramaShotReferenceResp> references = new ArrayList<>();
    private List<String> characterNames = new ArrayList<>();
    private List<String> propNames = new ArrayList<>();
    private String locationName;
    private Integer castCount;
    private String compiledPrompt;
    private String compiledFingerprint;
    private Boolean compiledStale;
    @Schema(description = "用户可见核心正文。无存库值且不是旧覆盖时，为按当前镜头结构现场渲染的初值")
    private String visiblePrompt;
    @Schema(description = "仍是旧的整段覆盖且用户尚未改存核心正文")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Boolean legacyOverride;
    private String promptOverride;
    private List<String> unboundNames = new ArrayList<>();
    private String status;
    private Long genTaskId;
    private String videoKey;
    private String videoUrl;
    @Schema(description = "VAD 字幕后处理状态")
    private String captionStatus;
    @Schema(description = "烧录字幕后的派生视频 key")
    private String captionVideoKey;
    @Schema(description = "烧录字幕后的派生视频地址")
    private String captionVideoUrl;
    @Schema(description = "字幕 JSON key")
    private String subtitleJsonKey;
    @Schema(description = "字幕 JSON 地址")
    private String subtitleJsonUrl;
    @Schema(description = "当前选用的生成记录 id")
    private Long selectedGenerationId;
    @Schema(description = "成片首帧对象 key")
    private String videoPosterKey;
    @Schema(description = "成片首帧预览地址")
    private String videoPosterUrl;
    @Schema(description = "成片落幅对象 key")
    private String videoTailKey;
    @Schema(description = "成片落幅预览地址")
    private String videoTailUrl;
    @Schema(description = "开场静帧对象 key（自动 i2i 或上传）")
    private String firstFrameKey;
    @Schema(description = "开场静帧预览地址")
    private String firstFrameUrl;
    @Schema(description = "本镜实际提交用的首帧 key（本镜静帧优先，否则同场上一镜落幅）")
    private String effectiveFirstFrameKey;
    @Schema(description = "本镜实际提交用的首帧预览地址")
    private String effectiveFirstFrameUrl;
    @Schema(description = "首帧来源：upload / auto / prev-tail / none")
    private String firstFrameSource;
    @Schema(description = "无有效首帧时的原因：no-prev / chapter-change / close-cut / prev-pending / prev-empty / tail-missing / ffmpeg-missing")
    private String firstFrameGap;
    @Schema(description = "同场上一镜已出片，可手动改用其落幅")
    private Boolean canUsePrevTail;
    @Schema(description = "正在自动画开场静帧")
    private Boolean generatingStill;
    @Schema(description = "本镜出片方式：r2v 参考图 / i2v 首尾帧")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String videoGenerateMode;
    @Schema(description = "本镜参考图是否衔接上一镜落幅；未写入时场内非第一镜默认衔接，显式 false 为不衔接")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Boolean videoChainPrevTail;
    @Schema(description = "本镜生成方式是否未写入（按参考图）")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Boolean videoModeInherited;
    @Schema(description = "本镜衔接落幅是否未写入（场内非第一镜默认衔接）")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Boolean videoChainInherited;
    @Schema(description = "实际出片方式")
    private String effectiveVideoGenerateMode;
    @Schema(description = "实际是否衔接上一镜落幅")
    private Boolean effectiveVideoChainPrevTail;
    @Schema(description = "出片当时的生成方式（r2v / i2v）；未出片为空")
    private String videoGeneratedMode;
    @Schema(description = "首尾帧链式：曾用哪一镜的落幅")
    private Long chainedFromShotId;
    @Schema(description = "首尾帧链式且上一镜已重出")
    private Boolean prevTailStale;
    @JsonProperty("generating")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "是否正在出片")
    private Boolean generating;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String genTaskStatus;
    private String genErrorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
