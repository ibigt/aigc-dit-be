package cn.aigc.dit.server.drama.text;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** Mechanical, lossless references for every identity and source-content item in a ScriptDoc. */
public record ScriptReferenceIndex(
        List<Mention> mentions,
        List<ActionSource> actions,
        List<DialogueSource> dialogues
) {
    public static final String TYPE_CHARACTER = "角色";
    public static final String TYPE_PROP = "道具";
    public static final String TYPE_LOCATION = "场景";

    public ScriptReferenceIndex {
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        dialogues = dialogues == null ? List.of() : List.copyOf(dialogues);
    }

    public static ScriptReferenceIndex from(ScriptDoc script) {
        List<Mention> mentions = new ArrayList<>();
        List<ActionSource> actions = new ArrayList<>();
        List<DialogueSource> dialogues = new ArrayList<>();
        if (script == null || script.getScenes() == null) {
            return new ScriptReferenceIndex(mentions, actions, dialogues);
        }
        for (int scenePos = 0; scenePos < script.getScenes().size(); scenePos++) {
            ScriptDoc.Scene scene = script.getScenes().get(scenePos);
            if (scene == null) continue;
            addMentions(mentions, scene.getCharacters(), scenePos, "characters", TYPE_CHARACTER);
            addMentions(mentions, scene.getProps(), scenePos, "props", TYPE_PROP);
            if (hasText(scene.getLocation())) {
                mentions.add(new Mention("scene:" + scenePos + ".location", TYPE_LOCATION,
                        scene.getLocation(), scenePos));
            }
            if (hasText(scene.getAction())) {
                actions.add(new ActionSource("scene:" + scenePos + ".action", scene.getAction(), scenePos));
            }
            if (scene.getDialogue() == null) continue;
            for (int dialoguePos = 0; dialoguePos < scene.getDialogue().size(); dialoguePos++) {
                ScriptDoc.Dialogue dialogue = scene.getDialogue().get(dialoguePos);
                if (dialogue == null) continue;
                dialogues.add(new DialogueSource("scene:" + scenePos + ".dialogue:" + dialoguePos,
                        dialogue.getSpeaker(), dialogue.getLine(), dialogue.getEmotion(), scenePos));
            }
        }
        return new ScriptReferenceIndex(mentions, actions, dialogues);
    }

    private static void addMentions(List<Mention> out, List<String> values, int scenePos,
                                    String field, String entityType) {
        if (values == null) return;
        for (int pos = 0; pos < values.size(); pos++) {
            String value = values.get(pos);
            if (hasText(value)) {
                out.add(new Mention("scene:" + scenePos + "." + field + ":" + pos,
                        entityType, value, scenePos));
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Mention(
            String id,
            @JsonProperty("entity_type") String entityType,
            String value,
            @JsonProperty("source_scene_index") int sourceSceneIndex
    ) {}

    public record ActionSource(
            String id,
            String value,
            @JsonProperty("source_scene_index") int sourceSceneIndex
    ) {}

    public record DialogueSource(
            String id,
            String speaker,
            String line,
            String emotion,
            @JsonProperty("source_scene_index") int sourceSceneIndex
    ) {}
}
