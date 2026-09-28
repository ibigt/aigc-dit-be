package cn.aigc.dit.server.script.entity;

import cn.aigc.dit.server.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "script_task", autoResultMap = true)
public class ScriptTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String mode;
    private String status;
    private String genre;
    private Integer targetEpisodes;
    private Long modelId;
    private String inputText;
    private String document;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> resultJson;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> attachments;

    private Long dramaSeriesId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
