package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.caption.CaptionCue;

import java.util.ArrayList;
import java.util.List;

/**
 * 把分镜对白均分到本镜时长上。一句占整段；多句按时长等分，不做口型对齐。
 */
public final class DramaShotCaptions {

    private DramaShotCaptions() {
    }

    public static List<CaptionCue> cues(String dialogue, int durationSec) {
        List<String> lines = DramaStoryboardCompiler.captionLines(dialogue);
        if (lines.isEmpty() || durationSec < 1) {
            return List.of();
        }
        double slot = durationSec / (double) lines.size();
        List<CaptionCue> cues = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            double start = i * slot;
            double end = i == lines.size() - 1 ? durationSec : (i + 1) * slot;
            cues.add(new CaptionCue(start, end, lines.get(i)));
        }
        return cues;
    }
}
