package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 乙交付的资产草稿清单。锁定 / 生图由编排层处理。
 */
@Data
public class AssetDraftList {

    private List<AssetDraft> assets = new ArrayList<>();

    @Data
    public static class AssetDraft {
        private String name;
        private String type;

        @JsonProperty("state_tag")
        @JsonAlias("stateTag")
        private String stateTag;

        private String description;

        @JsonProperty("identity_invariants")
        @JsonAlias("identityInvariants")
        private String identityInvariants;

        @JsonProperty("image_prompt")
        @JsonAlias("imagePrompt")
        private String imagePrompt;

        @JsonProperty("voice_lock")
        @JsonAlias("voiceLock")
        private String voiceLock;

        @JsonProperty("must_split")
        @JsonAlias("mustSplit")
        private Boolean mustSplit;

        @JsonProperty("needs_human")
        @JsonAlias("needsHuman")
        private Boolean needsHuman;
    }
}
