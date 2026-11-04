package cn.aigc.dit.server.entity;

import cn.aigc.dit.server.mybatis.JsonbTypeHandler;
import cn.aigc.dit.server.mybatis.JsonStringListTypeHandler;
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

    /** 模板编码：官方模板唯一标识，自定义工作流为空。 */
    private String templateCode;

    /** 模板分类：video / image / audio。 */
    private String category;

    /** 模板标签，用于标签筛选。 */
    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> tags;

    /** 模板封面图路径。 */
    private String coverPath;

    /** 模板版本号。 */
    private String version;

    /** 模型适配器：minimax-h3 / wan2.2 / ltx2.3 / sdxl / comfyui。 */
    private String modelAdapter;

    /** 节点拓扑缩略图数据：nodes 与 edges。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> topology;

    /** 实例化模板时套用的默认参数。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> defaultParams;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> graph;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
