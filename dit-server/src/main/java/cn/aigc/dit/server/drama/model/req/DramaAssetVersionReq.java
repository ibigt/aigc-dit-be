package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "基于某版本延伸新建角色版本")
public class DramaAssetVersionReq {

    @Size(max = 16)
    @Schema(description = "版本展示标签，如 湿身 / 校服；族内唯一，禁止含间隔号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String versionLabel;

    @Schema(description = "相对父版/基础版的变化描述", requiredMode = Schema.RequiredMode.REQUIRED)
    private String changeDescription;

    @Schema(description = "延伸父版本 id；缺省为当前资产 id")
    private Long parentAssetId;
}
