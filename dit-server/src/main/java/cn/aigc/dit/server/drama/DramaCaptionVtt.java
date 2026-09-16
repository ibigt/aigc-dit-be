package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.caption.CaptionCue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** WebVTT 生成器：每段只消费所属分镜的对白，成片时按镜头实际时长累计偏移。 */
public final class DramaCaptionVtt {

    public record Shot(String dialogue, double durationSec) {
    }

    private DramaCaptionVtt() {
    }

    public static String shot(String dialogue, double durationSec) {
        return render(List.of(new Shot(dialogue, durationSec)));
    }

    public static String render(List<Shot> shots) {
        List<CaptionCue> timeline = new ArrayList<>();
        double offset = 0D;
        if (shots != null) {
            for (Shot shot : shots) {
                if (shot == null) {
                    continue;
                }
                double duration = Math.max(0D, shot.durationSec());
                for (CaptionCue cue : DramaShotCaptions.cues(shot.dialogue(), duration)) {
                    timeline.add(new CaptionCue(
                            offset + cue.startSec(),
                            offset + cue.endSec(),
                            cue.text()));
                }
                offset += duration;
            }
        }
        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < timeline.size(); i++) {
            CaptionCue cue = timeline.get(i);
            vtt.append(i + 1).append('\n');
            vtt.append(timestamp(cue.startSec())).append(" --> ").append(timestamp(cue.endSec())).append('\n');
            vtt.append(cue.text().replace("-->", "→").trim()).append("\n\n");
        }
        return vtt.toString();
    }

    static String timestamp(double seconds) {
        long millis = Math.max(0L, Math.round(seconds * 1000D));
        long hours = millis / 3_600_000L;
        long minutes = (millis % 3_600_000L) / 60_000L;
        long secs = (millis % 60_000L) / 1000L;
        long ms = millis % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, secs, ms);
    }
}
