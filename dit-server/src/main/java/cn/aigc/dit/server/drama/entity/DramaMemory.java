package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目级记忆（V35）。
 * 一个 series_id + type 唯一。
 */
@Data
@TableName("drama_memory")
public class DramaMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private String type;        // characters / world / user_pref / style_pref
    private String content;
    private Long sourceEpisodeId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
