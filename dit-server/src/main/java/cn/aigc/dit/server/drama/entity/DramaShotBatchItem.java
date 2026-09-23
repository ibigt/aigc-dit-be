package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_shot_batch_item")
public class DramaShotBatchItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;
    private Long shotId;
    private Integer shotIndex;
    /** PENDING | RUNNING | SUCCEEDED | FAILED | CANCELLED */
    private String status;
    private Long genTaskId;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
