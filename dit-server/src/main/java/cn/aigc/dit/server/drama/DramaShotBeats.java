package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条分镜内部的镜头序列。不单独出片。
 * blocking 是本镜起始站位；beats 为 1–3 个镜头，每个镜头一件事、一个景别。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DramaShotBeats {

    private String blocking;
    private List<Beat> beats = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Beat {
        private String camera;
        private String action;
        private String continuity;
        private String dialogue;
    }

    public static boolean hasBeats(DramaShotBeats beats) {
        if (beats == null || beats.getBeats() == null) {
            return false;
        }
        for (Beat beat : beats.getBeats()) {
            if (beat != null && (StringUtils.hasText(beat.getCamera())
                    || StringUtils.hasText(beat.getAction())
                    || StringUtils.hasText(beat.getDialogue()))) {
                return true;
            }
        }
        return false;
    }

    /** 去掉空白镜头；一个可拍摄镜头都没有时视为未写镜头列表。 */
    public static DramaShotBeats normalize(DramaShotBeats raw) {
        if (raw == null) {
            return null;
        }
        List<Beat> beats = new ArrayList<>();
        if (raw.getBeats() != null) {
            for (Beat beat : raw.getBeats()) {
                Beat cleaned = clean(beat);
                if (cleaned != null) {
                    beats.add(cleaned);
                }
            }
        }
        if (beats.isEmpty()) {
            return null;
        }
        DramaShotBeats normalized = new DramaShotBeats();
        normalized.setBlocking(blankToNull(raw.getBlocking()));
        normalized.setBeats(beats);
        return normalized;
    }

    /** 各镜头台词按顺序拼接，供字幕和口播计时使用。 */
    public static String joinedDialogue(DramaShotBeats beats) {
        if (!hasBeats(beats)) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Beat beat : beats.getBeats()) {
            if (beat != null && StringUtils.hasText(beat.getDialogue())) {
                lines.add(beat.getDialogue().strip());
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    public static void syncDraftDialogue(ShotDraftList.ShotDraft draft) {
        if (draft == null || !hasBeats(draft.getShotBeats())) {
            return;
        }
        String joined = joinedDialogue(draft.getShotBeats());
        if (joined != null) {
            draft.setDialogue(joined);
        }
    }

    public static void syncStoredDialogue(DramaShot shot) {
        if (shot == null || !hasBeats(shot.getShotBeats())) {
            return;
        }
        String joined = joinedDialogue(shot.getShotBeats());
        if (joined != null) {
            shot.setDialogue(joined);
        }
    }

    public static List<String> mentionTexts(DramaShotBeats beats) {
        List<String> texts = new ArrayList<>();
        if (beats == null) {
            return texts;
        }
        if (StringUtils.hasText(beats.getBlocking())) {
            texts.add(beats.getBlocking().strip());
        }
        if (beats.getBeats() == null) {
            return texts;
        }
        for (Beat beat : beats.getBeats()) {
            if (beat == null) {
                continue;
            }
            addText(texts, beat.getCamera());
            addText(texts, beat.getAction());
            addText(texts, beat.getContinuity());
            addText(texts, beat.getDialogue());
        }
        return texts;
    }

    /** 道具被拿起、砸下的句子：本镜摘要和各镜头动作。 */
    public static List<String> actionTexts(DramaShot shot) {
        List<String> texts = new ArrayList<>();
        if (shot == null) {
            return texts;
        }
        addText(texts, shot.getNarrativeGoal());
        DramaShotBeats beats = shot.getShotBeats();
        if (beats == null || beats.getBeats() == null) {
            return texts;
        }
        for (Beat beat : beats.getBeats()) {
            if (beat != null) {
                addText(texts, beat.getAction());
            }
        }
        return texts;
    }

    public static Beat copyBeat(Beat beat) {
        if (beat == null) {
            return null;
        }
        Beat copy = new Beat();
        copy.setCamera(beat.getCamera());
        copy.setAction(beat.getAction());
        copy.setContinuity(beat.getContinuity());
        copy.setDialogue(beat.getDialogue());
        return copy;
    }

    private static Beat clean(Beat beat) {
        if (beat == null) {
            return null;
        }
        Beat cleaned = new Beat();
        cleaned.setCamera(blankToNull(beat.getCamera()));
        cleaned.setAction(blankToNull(beat.getAction()));
        cleaned.setContinuity(blankToNull(beat.getContinuity()));
        cleaned.setDialogue(blankToNull(beat.getDialogue()));
        if (cleaned.getCamera() == null && cleaned.getAction() == null
                && cleaned.getContinuity() == null && cleaned.getDialogue() == null) {
            return null;
        }
        return cleaned;
    }

    private static void addText(List<String> texts, String value) {
        if (StringUtils.hasText(value)) {
            texts.add(value.strip());
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
