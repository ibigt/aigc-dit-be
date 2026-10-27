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
@TableName(value = "workflow_run", autoResultMap = true)
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;
    private Long previousRunId;
    private Long userId;
    private Long modelId;
    private String status;
    private Integer progress;
    private Integer total;
    private String errorMessage;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> steps;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private OffsetDateTime finishedAt;
}
