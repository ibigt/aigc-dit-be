package cn.aigc.dit.server.drama.model.resp;

import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "分镜出片任务与当前分镜快照")
public class DramaShotGenerateResp {

    private DramaShotResp shot;
    private GenerateTaskResp task;
    /**
     * 仅针对已 convert（存在 origin sidecar）且已过期/缺失的参考图提示；
     * 未转过的外部图不列入（仍直送 Seedance）。
     */
    private List<String> originWarnings = new ArrayList<>();
}
