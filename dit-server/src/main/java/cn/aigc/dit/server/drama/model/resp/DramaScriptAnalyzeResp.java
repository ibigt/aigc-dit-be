package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "剧本分析结果")
public class DramaScriptAnalyzeResp {
    private DramaEpisodeResp episode;
    private List<DramaAssetResp> assets = new ArrayList<>();
    private DramaStoryboardResp storyboard;
    private Integer requestedEpisodeCount;
    private Integer requestedDurationSec;
    private Integer requestedShots;
    private Integer actualSceneCount;
    private Integer actualShotCount;
    private String engine;
    private List<String> warnings = new ArrayList<>();
}
