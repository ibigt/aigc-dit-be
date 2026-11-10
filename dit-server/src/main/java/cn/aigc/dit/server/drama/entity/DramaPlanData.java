package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 剧本解析中间态表（V34）。
 * key 取值：skeleton / script_v2 / asset_draft / shot_draft
 */
@Data
@TableName("drama_plan_data")
public class DramaPlanData {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long episodeId;
    private String key;

    /** JSONB，存原文 Schema 的 JSON 字符串。 */
    private String data;

    private String engine;
    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
