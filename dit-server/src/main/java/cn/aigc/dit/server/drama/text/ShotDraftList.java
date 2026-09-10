package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 乙交付的章节 + 分镜草稿。不含 compiled_prompt（由主力编译器写入）。
 */
@Data
public class ShotDraftList {

    private List<ChapterDraft> chapters = new ArrayList<>();
    private List<ShotDraft> shots = new ArrayList<>();

    @Data
    public static class ChapterDraft {
        private Integer index;
        private String name;

        @JsonProperty("scene_index")
        @JsonAlias("sceneIndex")
        private Integer sceneIndex;
    }

    @Data
    public static class ShotDraft {
        @JsonProperty("chapter_index")
        @JsonAlias("chapterIndex")
        private Integer chapterIndex;

        private Integer index;
        private String title;

        @JsonProperty("duration_sec")
        @JsonAlias("durationSec")
        private Integer durationSec;

        @JsonProperty("narrative_goal")
        @JsonAlias("narrativeGoal")
        private String narrativeGoal;

        private String scene;
        private String dialogue;

        @JsonProperty("continuity_in")
        @JsonAlias("continuityIn")
        private String continuityIn;

        @JsonProperty("continuity_out")
        @JsonAlias("continuityOut")
        private String continuityOut;

        @JsonProperty("character_names")
        @JsonAlias("characterNames")
        private List<String> characterNames = new ArrayList<>();

        @JsonProperty("prop_names")
        @JsonAlias("propNames")
        private List<String> propNames = new ArrayList<>();

        @JsonProperty("location_name")
        @JsonAlias("locationName")
        private String locationName;

        @JsonProperty("cast_count")
        @JsonAlias("castCount")
        private Integer castCount;
    }
}
