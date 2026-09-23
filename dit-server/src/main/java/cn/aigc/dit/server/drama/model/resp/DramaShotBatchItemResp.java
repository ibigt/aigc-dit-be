package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "分镜批量出片条目")
public class DramaShotBatchItemResp {

    private Long id;
    private Long jobId;
    private Long shotId;
    private Integer shotIndex;
    private String status;
    private Long genTaskId;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
