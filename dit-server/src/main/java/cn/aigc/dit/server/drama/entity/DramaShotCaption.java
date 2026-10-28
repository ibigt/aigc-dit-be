package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_shot_caption")
public class DramaShotCaption {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shotId;
    private Long genTaskId;
    private Long userId;
    private String sourceVideoKey;
    private String audioKey;
    private String subtitleJsonKey;
    private String captionVideoKey;
    private String status;
    private String processorVersion;
    private String errorMessage;
    /** Soft retry counter for worker exponential backoff. */
    private Integer attemptCount;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
