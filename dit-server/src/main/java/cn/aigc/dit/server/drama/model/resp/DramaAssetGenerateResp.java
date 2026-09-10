package cn.aigc.dit.server.drama.model.resp;

import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "资产 t2i 任务与当前资产快照")
public class DramaAssetGenerateResp {

    private DramaAssetResp asset;
    private GenerateTaskResp task;
}
