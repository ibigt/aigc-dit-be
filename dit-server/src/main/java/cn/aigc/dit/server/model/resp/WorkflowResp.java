package cn.aigc.dit.server.model.resp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "高级工作台工作流")
public class WorkflowResp {
    private Long id;
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
    private Map<String, Object> graph;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Schema(description = "模板编码，官方模板唯一标识")
    private String templateCode;

    @Schema(description = "模板分类：video / image / audio")
    private String category;

    @Schema(description = "模板标签")
    private List<String> tags;

    @Schema(description = "模板封面图路径")
    private String coverPath;

    @Schema(description = "模板版本号")
    private String version;

    @Schema(description = "模型适配器")
    private String modelAdapter;

    @Schema(description = "节点拓扑缩略图数据")
    private Map<String, Object> topology;

    @Schema(description = "实例化时套用的默认参数")
    private Map<String, Object> defaultParams;
}
