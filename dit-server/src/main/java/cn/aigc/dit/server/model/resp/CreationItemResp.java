package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "作品条目")
public class CreationItemResp {
    private Long id;
    private Long sessionId;
    private Long taskId;
    private Long modelId;
    private String modelName;
    private String title;
    private String category;
    private String type;
    private String storageKey;
    private String posterKey;
    private String url;
    private String posterUrl;
    private String src;
    private String poster;
    private String prompt;
    private String ratio;
    private String resolution;
    private Integer durationSec;
    private String duration;
    private Long creditCost;
    private Integer copyIndex;
    private OffsetDateTime createdAt;
    @Schema(description = "生成耗时，如 12秒、1分05秒")
    private String elapsed;
    @Schema(description = "quick 快速创作，drama 短剧")
    private String projectKind;
    private Long projectId;
    @Schema(description = "筛选项，与项目列表的 key 一致")
    private String projectKey;
    private String projectName;
}
