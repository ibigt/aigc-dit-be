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

/** 画布资产：节点配置快照。与 {@link Workflow} 共用 JSONB 映射口径。 */
@Data
@TableName(value = "canvas_asset", autoResultMap = true)
public class CanvasAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    /** 单节点资产记节点 kind，多节点记 multi。 */
    private String nodeKind;
    private Integer nodeCount;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> graph;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
