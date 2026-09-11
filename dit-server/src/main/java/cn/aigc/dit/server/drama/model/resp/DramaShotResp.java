package cn.aigc.dit.server.drama.model.resp;

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
    private List<String> unboundNames = new ArrayList<>();
    private String status;
    private Long genTaskId;
    private String videoKey;
    private String videoUrl;
    @Schema(description = "是否正在出片")
    private Boolean generating;
    private String genTaskStatus;
    private String genErrorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
