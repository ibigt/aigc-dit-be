package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "分镜批量出片任务")
public class DramaShotBatchGenerateResp {

    private Long id;
    private Long episodeId;
    private Long seriesId;
    private String status;
    private String videoGenerateMode;
    private Boolean chainPrevTail;
    private String i2vFirstFrameMode;
    private Boolean firstShotAutoStill;
    private Boolean concurrent;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<DramaShotBatchItemResp> items = new ArrayList<>();
}
