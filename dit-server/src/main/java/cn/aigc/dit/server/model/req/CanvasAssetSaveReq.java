package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "保存画布资产")
public class CanvasAssetSaveReq {

    @NotBlank(message = "资产名称不能为空")
    @Size(max = 128)
    private String name;

    @Schema(description = "节点子集 {nodes, edges}；服务端会剥离运行态与产物")
    private Map<String, Object> graph;
}
