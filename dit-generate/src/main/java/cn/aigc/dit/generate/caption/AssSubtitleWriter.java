package cn.aigc.dit.generate.caption;

import java.util.List;
import java.util.Locale;

final class AssSubtitleWriter {

    private AssSubtitleWriter() {
    }

    static String render(List<CaptionCue> cues) {
        StringBuilder out = new StringBuilder();
        out.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: 1920\n")
                .append("PlayResY: 1080\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,Source Han Sans CN,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,80,80,72,1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        if (cues != null) {
            for (CaptionCue cue : cues) {
                if (cue == null || !cue.hasText()) continue;
                out.append("Dialogue: 0,")
                        .append(timestamp(cue.startSec())).append(',')
                        .append(timestamp(cue.endSec())).append(",Default,,0,0,72,,")
                        .append(escape(CaptionTexts.wrap(cue.text())))
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r\n", "\\N")
                .replace("\n", "\\N");
    }

    private static String timestamp(double seconds) {
        long centiseconds = Math.max(0L, Math.round(seconds * 100D));
        long hours = centiseconds / 360000L;
        long minutes = (centiseconds % 360000L) / 6000L;
        long secondsPart = (centiseconds % 6000L) / 100L;
        long hundredths = centiseconds % 100L;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, secondsPart, hundredths);
    }
}
