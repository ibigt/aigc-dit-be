package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v2 剧本分析响应。在 v1 DramaScriptAnalyzeResp 基础上叠加：
 * - engine: "v2-agent" 标识
 * - failedAt: 失败步骤（null = 全部成功）
 * - stepErrors: 每步错误信息
 * - stepRetries: 每步重试次数
 * - stepSupervisorRepaired: 每步是否走过 supervisor 修复
 * - promptTokens / completionTokens / durationMs: 监控指标
 *
 * 前端可用此对象：
 * - 显示 engine 标识（v2-agent vs llm）
 * - 失败时按 failedAt 提示用户在哪个环节重试
 * - 监控页读 token / durationMs
 */
@Data
@Schema(description = "v2 剧本分析结果")
public class DramaScriptAnalyzeV2Resp {

    @Schema(description = "v1 兼容字段：分场后的 episode 信息")
    private DramaEpisodeResp episode;

    @Schema(description = "v1 兼容字段：资产清单")
    private List<DramaAssetResp> assets = new ArrayList<>();

    @Schema(description = "v1 兼容字段：分镜草稿")
    private DramaStoryboardResp storyboard;

    @Schema(description = "实际场次数")
    private Integer actualSceneCount;

    @Schema(description = "实际镜次数")
    private Integer actualShotCount;

    @Schema(description = "引擎标识：v2-agent")
    private String engine;

    @Schema(description = "警告信息")
    private List<String> warnings = new ArrayList<>();

    @Schema(description = "v2 扩展：失败步骤（null = 全部成功）")
    private String failedAt;

    @Schema(description = "v2 扩展：每步错误信息（key=skeleton/script/assets/shots）")
    private Map<String, String> stepErrors;

    @Schema(description = "v2 扩展：每步内部重试次数（不含 supervisor repair）")
    private Map<String, Integer> stepRetries;

    @Schema(description = "v2 扩展：每步是否走过 supervisor 修复")
    private Map<String, Boolean> stepSupervisorRepaired;

    @Schema(description = "v2 扩展：4 步累计 prompt token")
    private Long promptTokens;

    @Schema(description = "v2 扩展：4 步累计 completion token")
    private Long completionTokens;

    @Schema(description = "v2 扩展：全流程耗时（毫秒）")
    private Long durationMs;
}
