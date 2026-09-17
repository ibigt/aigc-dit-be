package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "管理端生成任务（请求日志 / 任务队列）")
public class AdminGenTaskResp {
    private Long id;
    private String requestNo;
    private String taskNo;
    private String category;
    private String categoryLabel;
    private String userName;
    private String modelName;
    private String source;
    private String status;
    private String statusLabel;
    private Integer progress;
    private String errorMessage;
    private Long creditCost;
    private Double durationSeconds;
    private Double waitSeconds;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
