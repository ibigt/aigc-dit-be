package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "漫剧资产生成历史记录")
public class DramaAssetGenerationResp {

    private Long id;
    private Long assetId;
    private String storageKey;
    private String url;
    private Long genTaskId;
    private String source;
    private String label;
    private String resolution;
    /** 生成该图时任务绑定的模型展示名。上传、引用或任务已不在时为空。 */
    private String modelName;
    private Long libraryAssetId;
    private Boolean selected;
    private Boolean imported;
    /** Active / Processing / Failed。没有审核记录时为空。 */
    private String arkReviewStatus;
    private String arkReviewError;
    private OffsetDateTime createdAt;
}
