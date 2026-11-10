package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 故事骨架。覆盖 Toonflow 的 <storySkeleton> 标签输出 + <adaptationStrategy> 部分字段。
 *
 * 由 OutlineSkeletonAgent 生成，作为下游 ScriptParseAgent 的引导输入。
 *
 * @param logline              全剧一句话
 * @param stylePrefix          视觉/叙事风格短句
 * @param episodes             与 targetEpisodes 等长的分集规划
 * @param charactersRecurrent  跨集复用的核心人物（去重后）
 * @param worldRules           世界观规则
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkeletonDoc(
        @JsonProperty("logline") String logline,
        @JsonProperty("style_prefix") String stylePrefix,
        @JsonProperty("episodes") List<EpisodeOutline> episodes,
        @JsonProperty("characters_recurrent") List<String> charactersRecurrent,
        @JsonProperty("world_rules") List<String> worldRules
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpisodeOutline(
            @JsonProperty("index") int index,
            @JsonProperty("title") String title,
            @JsonProperty("logline") String logline,
            @JsonProperty("hook") String hook,
            @JsonProperty("characters") List<String> characters,
            @JsonProperty("scenes_hint") List<SceneHint> scenesHint,
            @JsonProperty("climax") String climax,
            @JsonProperty("next_episode_tease") String nextEpisodeTease
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SceneHint(
            @JsonProperty("heading") String heading,
            @JsonProperty("purpose") String purpose
    ) {}

    /** 返回空骨架（用于 supervisor repair 失败后降级）。 */
    public static SkeletonDoc empty() {
        return new SkeletonDoc("", "", List.of(), List.of(), List.of());
    }
}
