package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_series")
public class DramaSeries {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String styleKey;
    private String stylePrefix;
    private String aspectRatio;
    private String defaultResolution;
    private Integer defaultDurationSec;
    private Integer plannedEpisodeCount;
    private Long textModelId;
    private Long imageModelId;
    private String imageAdapterKey;
    private Long videoModelId;
    private String videoAdapterKey;
    private String videoGenerateMode;
    private Boolean videoChainPrevTail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
