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
@TableName(value = "gen_task", autoResultMap = true)
public class GenTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long modelId;
    private String category;
    private String status;
    private Integer progress;
    private String errorMessage;
    private String prompt;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> params;

    private String outputStorageKey;
    private String posterKey;
    private String providerJobId;
    private Long creditCost;
    private Integer copies;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> outputKeys;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private OffsetDateTime finishedAt;
}
