package cn.aigc.dit.server.drama.text;

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
        boolean bothDialogue = StringUtils.hasText(left.getDialogue()) && StringUtils.hasText(right.getDialogue());
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
            int chars = spokenChars(left.getDialogue()) + spokenChars(right.getDialogue());
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
