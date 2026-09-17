package cn.aigc.dit.server.mapper.stat;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AdminGenTaskRow {
    private Long id;
    private Long userId;
    private String userName;
    private Long modelId;
    private String modelName;
    private String category;
    private String status;
    private Integer progress;
    private String errorMessage;
    private String source;
    private Long creditCost;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
