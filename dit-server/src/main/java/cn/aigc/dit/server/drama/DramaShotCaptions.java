package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.caption.CaptionCue;

import java.util.ArrayList;
import java.util.List;

/**
 * 把当前分镜对白分配到本镜时长上。一句占整段；多句按有效字符数分配，不做口型对齐。
 */
public final class DramaShotCaptions {

    private DramaShotCaptions() {
    }

    public static List<CaptionCue> cues(String dialogue, int durationSec) {
        return cues(dialogue, (double) durationSec);
    }

    public static List<CaptionCue> cues(String dialogue, double durationSec) {
        List<String> lines = DramaStoryboardCompiler.captionLines(dialogue);
        if (lines.isEmpty() || durationSec < 1) {
            return List.of();
        }
        long totalWeight = lines.stream().mapToLong(DramaShotCaptions::weight).sum();
        List<CaptionCue> cues = new ArrayList<>(lines.size());
        double cursor = 0D;
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            double start = cursor;
            double end = i == lines.size() - 1
                    ? durationSec
                    : Math.min(durationSec, cursor + durationSec * weight(text) / totalWeight);
            cues.add(new CaptionCue(start, end, text));
            cursor = end;
        }
        return cues;
    }

    private static long weight(String text) {
        if (text == null || text.isBlank()) {
            return 1L;
        }
        long count = text.codePoints().filter(Character::isLetterOrDigit).count();
        return Math.max(1L, count);
    }
}
