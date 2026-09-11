package cn.aigc.dit.server.drama.model.resp;

import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分镜出片任务与当前分镜快照")
public class DramaShotGenerateResp {

    private DramaShotResp shot;
    private GenerateTaskResp task;
}
