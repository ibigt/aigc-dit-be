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
    private Long libraryAssetId;
    private Boolean selected;
    private Boolean imported;
    private OffsetDateTime createdAt;
}
