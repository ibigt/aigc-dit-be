package cn.aigc.dit.server.entity;

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
@TableName(value = "ai_model", autoResultMap = true)
public class AiModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String modelKey;
    private String provider;
    private String endpoint;
    private String apiKeyEnc;
    private String category;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> pricing;

    private Boolean enabled;
    private String description;
    private String badge;
    private String logo;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> capabilities;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> adapterConfig;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
