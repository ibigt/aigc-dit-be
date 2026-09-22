package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_shot_generation")
public class DramaShotGeneration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shotId;
    private Long userId;
    private String videoKey;
    private String videoPosterKey;
    private String videoTailKey;
    private Long genTaskId;
    /** generate | upload */
    private String source;
    private String label;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
