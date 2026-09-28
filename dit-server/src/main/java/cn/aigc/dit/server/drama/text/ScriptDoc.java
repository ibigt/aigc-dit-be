package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分场剧本文档（入库 {@code drama_episode.script}）。字段名与乙交付合同一致。
 */
@Data
public class ScriptDoc {

    private String title;
    private String logline;

    @JsonProperty("style_prefix")
    @JsonAlias("stylePrefix")
    private String stylePrefix;

    private List<Scene> scenes = new ArrayList<>();

    @Data
    public static class Scene {
        private Integer index;
        private String slug;
        private String heading;
        private List<String> characters = new ArrayList<>();
        private List<String> props = new ArrayList<>();
        private String location;
        private String time;
        private Boolean interior;
        private String action;
        private String sound;
        private List<Dialogue> dialogue = new ArrayList<>();
    }

    @Data
    public static class Dialogue {
        private String speaker;
        private String line;
        /** 闭集情绪：高兴/愤怒/悲伤/恐惧/惊讶/厌恶/紧张；不明显则空。旁白不得带情绪。 */
        private String emotion;
    }
}
