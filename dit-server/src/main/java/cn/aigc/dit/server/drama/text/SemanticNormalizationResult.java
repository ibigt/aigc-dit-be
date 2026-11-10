package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Model output for one episode-level semantic normalization decision. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticNormalizationResult {
    @JsonProperty("normalized_script")
    private ScriptDoc normalizedScript;

    @JsonProperty("entity_groups")
    private List<EntityGroup> entityGroups = new ArrayList<>();

    @JsonProperty("scene_lineage")
    private List<SceneLineage> sceneLineage = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityGroup {
        @JsonProperty("entity_type")
        private String entityType;
        @JsonProperty("canonical_mention_id")
        private String canonicalMentionId;
        @JsonProperty("member_mention_ids")
        private List<String> memberMentionIds = new ArrayList<>();
        private Double confidence;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SceneLineage {
        @JsonProperty("normalized_scene_index")
        private Integer normalizedSceneIndex;
        @JsonProperty("source_scene_indexes")
        private List<Integer> sourceSceneIndexes = new ArrayList<>();
        @JsonProperty("action_source_ids")
        private List<String> actionSourceIds = new ArrayList<>();
        @JsonProperty("dialogue_source_ids")
        private List<String> dialogueSourceIds = new ArrayList<>();
    }
}
