package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("creation")
public class Creation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Long userId;
    private Long taskId;
    private Long modelId;
    private String title;
    private String category;
    private String storageKey;
    private String posterKey;
    private String prompt;
    private String ratio;
    private String resolution;
    private Integer durationSec;
    private Long creditCost;
    private Integer copyIndex;
    private String projectKind;
    private Long projectId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
