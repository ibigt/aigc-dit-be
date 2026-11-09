package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Schema(description = "画布资产")
public class CanvasAssetResp {

    private Long id;
    private String name;
    private String nodeKind;
    private Integer nodeCount;
    /** 列表不返回图，点「插入画布」时再按 id 取，避免一次拉回所有资产的大 payload。 */
    private Map<String, Object> graph;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
