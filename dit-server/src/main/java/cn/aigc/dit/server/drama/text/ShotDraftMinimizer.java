package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaDurations;
import cn.aigc.dit.server.drama.DramaShotBeats;
import cn.aigc.dit.server.drama.DramaStoryboardCompiler;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把模型过度拆碎、但仍可由一次视频生成承载的同场相邻草稿合并。
 * 只合并同场连续、且合计不超过单镜上限（通常 15 秒）的内容；
 * 同场连续对谈（两边都有对白）允许合并；单镜本身需要 15 秒完全允许，此处不压缩单镜时长。
 */
public final class ShotDraftMinimizer {

    private ShotDraftMinimizer() {
    }

    public static ShotDraftList minimize(ShotDraftList list, int maxDurationSec) {
        if (list == null || list.getShots() == null) {
            return list;
        }
        if (list.getShots().size() < 2) {
            List<ShotDraftList.ShotDraft> split = new ArrayList<>();
            for (ShotDraftList.ShotDraft shot : list.getShots()) {
                if (shot != null) {
                    split.addAll(splitOverlongBeats(shot, maxDurationSec));
                }
            }
            renumber(split);
            list.setShots(split);
            return list;
        }
        List<ShotDraftList.ShotDraft> minimized = new ArrayList<>();
        for (ShotDraftList.ShotDraft next : list.getShots()) {
            if (next == null) {
                continue;
            }
            if (minimized.isEmpty()) {
                minimized.add(next);
                continue;
            }
            ShotDraftList.ShotDraft current = minimized.get(minimized.size() - 1);
            if (canMerge(current, next, maxDurationSec)) {
                mergeInto(current, next);
            } else {
                minimized.add(next);
            }
        }
        renumber(minimized);
        List<ShotDraftList.ShotDraft> split = new ArrayList<>();
        for (ShotDraftList.ShotDraft shot : minimized) {
            split.addAll(splitOverlongBeats(shot, maxDurationSec));
        }
        renumber(split);
        list.setShots(split);
        return list;
    }

    private static boolean canMerge(ShotDraftList.ShotDraft left, ShotDraftList.ShotDraft right, int maxDurationSec) {
        if (!Objects.equals(left.getChapterIndex(), right.getChapterIndex())) {
            return false;
        }
        if (!Objects.equals(clean(left.getLocationName()), clean(right.getLocationName()))) {
            return false;
        }
        boolean bothDialogue = StringUtils.hasText(dialogueOf(left)) && StringUtils.hasText(dialogueOf(right));
        // 同场连续对谈：不要求 continuity 字面相等（一句一镜时常写成不同出入点）。
        // 纯动作链仍要求衔接点对齐，避免门开/门关一类矛盾戏并在一起。
        if (!bothDialogue) {
            String continuityOut = clean(left.getContinuityOut());
            String continuityIn = clean(right.getContinuityIn());
            if (continuityOut == null || continuityIn == null || !continuityOut.equals(continuityIn)) {
                return false;
            }
            if (!sameCharacterSet(left.getCharacterNames(), right.getCharacterNames())) {
                return false;
            }
        }
        int leftDuration = left.getDurationSec() == null ? maxDurationSec : left.getDurationSec();
        int rightDuration = right.getDurationSec() == null ? maxDurationSec : right.getDurationSec();
        if (leftDuration + rightDuration > maxDurationSec) {
            return false;
        }
        if (bothDialogue) {
            // 合并后的台词也要在合计时长里念得完（4 字/秒），
            // 否则对谈并进一镜只会制造 15 秒封顶、台词念一半就被切掉的超载镜。
            int chars = spokenChars(dialogueOf(left)) + spokenChars(dialogueOf(right));
            return (int) Math.ceil(chars / (double) DramaTextServiceImpl.SPOKEN_CHARS_PER_SECOND)
                    <= leftDuration + rightDuration;
        }
        return true;
    }

    /** 与台词时长护栏同一口径：按字幕行统计口播字数（去说话人前缀，标点不发音不计）。 */
    private static int spokenChars(String dialogue) {
        int chars = 0;
        for (String line : DramaStoryboardCompiler.captionLines(dialogue)) {
            chars += DramaStoryboardCompiler.speakableChars(line);
        }
        return chars;
    }

    private static String dialogueOf(ShotDraftList.ShotDraft shot) {
        if (shot == null) {
            return null;
        }
        if (DramaShotBeats.hasBeats(shot.getShotBeats())) {
            String joined = DramaShotBeats.joinedDialogue(shot.getShotBeats());
            if (StringUtils.hasText(joined)) {
                return joined;
            }
        }
        return shot.getDialogue();
    }

    /**
     * 口播超过单镜上限，或一条分镜里镜头多于 3 个时，按镜头边界拆开。
     * 拆开后每条分镜的动作句只留该段第一个镜头，不再把两句动作拼回一句。
     */
    static List<ShotDraftList.ShotDraft> splitOverlongBeats(ShotDraftList.ShotDraft shot, int maxDurationSec) {
        if (shot == null || !DramaShotBeats.hasBeats(shot.getShotBeats())) {
            return shot == null ? List.of() : List.of(shot);
        }
        int budget = Math.max(1, maxDurationSec) * DramaTextServiceImpl.SPOKEN_CHARS_PER_SECOND;
        List<List<DramaShotBeats.Beat>> groups = packBeats(shot.getShotBeats().getBeats(), budget, 3);
        if (groups.size() <= 1) {
            DramaShotBeats.syncDraftDialogue(shot);
            return List.of(shot);
        }
        List<ShotDraftList.ShotDraft> parts = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            parts.add(copyWithBeats(shot, groups.get(i), i == 0, i == groups.size() - 1));
        }
        return parts;
    }

    private static List<List<DramaShotBeats.Beat>> packBeats(
            List<DramaShotBeats.Beat> beats, int budget, int maxBeats) {
        List<List<DramaShotBeats.Beat>> groups = new ArrayList<>();
        List<DramaShotBeats.Beat> current = new ArrayList<>();
        int chars = 0;
        for (DramaShotBeats.Beat beat : beats) {
            if (beat == null) {
                continue;
            }
            int lineChars = spokenChars(beat.getDialogue());
            boolean overflow = !current.isEmpty()
                    && (current.size() >= maxBeats || chars + lineChars > budget);
            if (overflow) {
                groups.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(beat);
            chars += lineChars;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private static ShotDraftList.ShotDraft copyWithBeats(
            ShotDraftList.ShotDraft origin,
            List<DramaShotBeats.Beat> beats,
            boolean first,
            boolean last) {
        ShotDraftList.ShotDraft copy = new ShotDraftList.ShotDraft();
        copy.setChapterIndex(origin.getChapterIndex());
        copy.setIndex(origin.getIndex());
        copy.setTitle(origin.getTitle());
        DramaShotBeats shotBeats = new DramaShotBeats();
        shotBeats.setBlocking(origin.getShotBeats() == null ? null : origin.getShotBeats().getBlocking());
        List<DramaShotBeats.Beat> copied = new ArrayList<>();
        for (DramaShotBeats.Beat beat : beats) {
            copied.add(DramaShotBeats.copyBeat(beat));
        }
        shotBeats.setBeats(copied);
        copy.setShotBeats(shotBeats);
        DramaShotBeats.Beat head = copied.get(0);
        copy.setNarrativeGoal(head.getAction() == null ? "" : head.getAction());
        copy.setCamera(head.getCamera());
        copy.setScene(origin.getScene());
        copy.setAmbiance(origin.getAmbiance());
        String dialogue = DramaShotBeats.joinedDialogue(shotBeats);
        copy.setDialogue(dialogue == null ? "" : dialogue);
        int chars = spokenChars(dialogue);
        int seconds = chars == 0
                ? (origin.getDurationSec() == null ? 5 : origin.getDurationSec())
                : (int) Math.ceil(chars / (double) DramaTextServiceImpl.SPOKEN_CHARS_PER_SECOND);
        copy.setDurationSec(DramaDurations.clamp(seconds));
        copy.setContinuityIn(first ? origin.getContinuityIn() : nullToEmpty(head.getContinuity()));
        DramaShotBeats.Beat tail = copied.get(copied.size() - 1);
        copy.setContinuityOut(last ? origin.getContinuityOut() : nullToEmpty(tail.getContinuity()));
        copy.setCharacterNames(new ArrayList<>(origin.getCharacterNames() == null ? List.of() : origin.getCharacterNames()));
        copy.setPropNames(new ArrayList<>(origin.getPropNames() == null ? List.of() : origin.getPropNames()));
        copy.setLocationName(origin.getLocationName());
        copy.setCastCount(origin.getCastCount());
        return copy;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean sameCharacterSet(List<String> left, List<String> right) {
        return normalizeNames(left).equals(normalizeNames(right));
    }

    private static Set<String> normalizeNames(List<String> names) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (StringUtils.hasText(name)) {
                    values.add(name.strip());
                }
            }
        }
        return values;
    }

    private static void mergeInto(ShotDraftList.ShotDraft target, ShotDraftList.ShotDraft addition) {
        target.setDurationSec(value(target.getDurationSec()) + value(addition.getDurationSec()));
        if (DramaShotBeats.hasBeats(target.getShotBeats()) || DramaShotBeats.hasBeats(addition.getShotBeats())) {
            DramaShotBeats merged = new DramaShotBeats();
            merged.setBlocking(firstBlocking(target, addition));
            List<DramaShotBeats.Beat> beats = new ArrayList<>();
            beats.addAll(asBeats(target));
            beats.addAll(asBeats(addition));
            merged.setBeats(beats);
            target.setShotBeats(merged);
            String dialogue = DramaShotBeats.joinedDialogue(merged);
            target.setDialogue(dialogue == null ? "" : dialogue);
            if (!StringUtils.hasText(target.getNarrativeGoal()) && !beats.isEmpty()) {
                target.setNarrativeGoal(beats.get(0).getAction());
            }
        } else {
            target.setNarrativeGoal(join(target.getNarrativeGoal(), addition.getNarrativeGoal(), ""));
            target.setDialogue(join(target.getDialogue(), addition.getDialogue(), "\n"));
        }
        target.setCharacterNames(union(target.getCharacterNames(), addition.getCharacterNames()));
        target.setPropNames(union(target.getPropNames(), addition.getPropNames()));
        target.setCastCount(target.getCharacterNames().size());
        target.setContinuityOut(addition.getContinuityOut());
    }

    private static String firstBlocking(ShotDraftList.ShotDraft left, ShotDraftList.ShotDraft right) {
        if (left.getShotBeats() != null && StringUtils.hasText(left.getShotBeats().getBlocking())) {
            return left.getShotBeats().getBlocking().strip();
        }
        if (right.getShotBeats() != null && StringUtils.hasText(right.getShotBeats().getBlocking())) {
            return right.getShotBeats().getBlocking().strip();
        }
        return null;
    }

    /** 已有镜头列表就原样接上；没有的一侧收成一个镜头，避免把两句动作拼回 narrative_goal。 */
    private static List<DramaShotBeats.Beat> asBeats(ShotDraftList.ShotDraft shot) {
        List<DramaShotBeats.Beat> beats = new ArrayList<>();
        if (DramaShotBeats.hasBeats(shot.getShotBeats())) {
            for (DramaShotBeats.Beat beat : shot.getShotBeats().getBeats()) {
                DramaShotBeats.Beat copy = DramaShotBeats.copyBeat(beat);
                if (copy != null) {
                    beats.add(copy);
                }
            }
            return beats;
        }
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera(shot.getCamera());
        beat.setAction(shot.getNarrativeGoal());
        beat.setContinuity(shot.getContinuityIn());
        beat.setDialogue(shot.getDialogue());
        beats.add(beat);
        return beats;
    }

    private static void renumber(List<ShotDraftList.ShotDraft> shots) {
        Integer chapter = null;
        int index = 0;
        for (ShotDraftList.ShotDraft shot : shots) {
            if (!Objects.equals(chapter, shot.getChapterIndex())) {
                chapter = shot.getChapterIndex();
                index = 0;
            }
            shot.setIndex(++index);
        }
    }

    private static List<String> union(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (left != null) {
            values.addAll(left);
        }
        if (right != null) {
            values.addAll(right);
        }
        values.removeIf(value -> !StringUtils.hasText(value));
        return new ArrayList<>(values);
    }

    private static String join(String left, String right, String separator) {
        if (!StringUtils.hasText(left)) {
            return StringUtils.hasText(right) ? right.strip() : "";
        }
        if (!StringUtils.hasText(right)) {
            return left.strip();
        }
        return left.strip() + separator + right.strip();
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
