package cn.aigc.dit.server.entity;

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
@TableName(value = "workflow", autoResultMap = true)
public class Workflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String description;
    private String type;
    private Long modelId;
    private String modelLabel;
    private String origin;
    private String demoKey;
    private String sourcePath;
    private Boolean builtin;
    private Integer nodeCount;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> graph;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
