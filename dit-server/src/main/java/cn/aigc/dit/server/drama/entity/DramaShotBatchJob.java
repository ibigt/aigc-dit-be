package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_shot_batch_job")
public class DramaShotBatchJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long episodeId;
    private Long seriesId;
    private Long userId;
    /** PENDING | RUNNING | SUCCEEDED | FAILED | CANCELLED */
    private String status;
    private String videoGenerateMode;
    private Boolean chainPrevTail;
    /** auto | prev_tail | null */
    private String i2vFirstFrameMode;
    private Boolean firstShotAutoStill;
    private Boolean concurrent;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
