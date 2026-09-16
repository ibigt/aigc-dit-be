package cn.aigc.dit.server.drama.text;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 把模型过度拆分、但仍可由一次视频生成承载的同场相邻草稿合并。
 * 这里只消费模型已经给出的顺序、场次和时长，不推断或创作剧情。
 */
public final class ShotDraftMinimizer {

    private ShotDraftMinimizer() {
    }

    public static ShotDraftList minimize(ShotDraftList list, int maxDurationSec) {
        if (list == null || list.getShots() == null || list.getShots().size() < 2) {
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
        list.setShots(minimized);
        return list;
    }

    private static boolean canMerge(ShotDraftList.ShotDraft left, ShotDraftList.ShotDraft right, int maxDurationSec) {
        if (!Objects.equals(left.getChapterIndex(), right.getChapterIndex())) {
            return false;
        }
        if (!Objects.equals(clean(left.getLocationName()), clean(right.getLocationName()))) {
            return false;
        }
        String continuityOut = clean(left.getContinuityOut());
        String continuityIn = clean(right.getContinuityIn());
        if (continuityOut == null || continuityIn == null || !continuityOut.equals(continuityIn)) {
            return false;
        }
        int leftDuration = left.getDurationSec() == null ? maxDurationSec : left.getDurationSec();
        int rightDuration = right.getDurationSec() == null ? maxDurationSec : right.getDurationSec();
        return leftDuration + rightDuration <= maxDurationSec;
    }

    private static void mergeInto(ShotDraftList.ShotDraft target, ShotDraftList.ShotDraft addition) {
        target.setDurationSec(value(target.getDurationSec()) + value(addition.getDurationSec()));
        target.setNarrativeGoal(join(target.getNarrativeGoal(), addition.getNarrativeGoal(), ""));
        target.setDialogue(join(target.getDialogue(), addition.getDialogue(), "\n"));
        target.setCharacterNames(union(target.getCharacterNames(), addition.getCharacterNames()));
        target.setPropNames(union(target.getPropNames(), addition.getPropNames()));
        target.setCastCount(target.getCharacterNames().size());
        target.setContinuityOut(addition.getContinuityOut());
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
