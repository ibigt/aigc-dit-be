package cn.aigc.dit.server.drama.entity;

import cn.aigc.dit.server.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "drama_episode", autoResultMap = true)
public class DramaEpisode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private Long userId;
    private Integer episodeNumber;
    private String name;
    private String sourceText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> script;

    private String status;
    private Long inheritedFromEpisodeId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
