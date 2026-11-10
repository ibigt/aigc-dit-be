package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 剧本 Prompt 版本表（V33）。
 * 一个 key 可有多个版本，仅一条 active=true。
 */
@Data
@TableName("script_prompt")
public class ScriptPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String key;
    private String content;
    private Integer version;
    private Boolean active;
    private String attribution;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
