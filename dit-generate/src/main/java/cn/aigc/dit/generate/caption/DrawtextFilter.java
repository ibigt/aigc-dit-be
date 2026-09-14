package cn.aigc.dit.generate.caption;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 组装 ffmpeg drawtext 滤镜。每条 Overlay 对应一句台词的显示区间。
 */
final class DrawtextFilter {

    record Overlay(double startSec, double endSec, Path textFile) {
    }

    private DrawtextFilter() {
    }

    static String build(Path fontFile, List<Overlay> overlays) {
        String font = escapePath(fontFile);
        List<String> parts = new ArrayList<>();
        for (Overlay overlay : overlays) {
            String textFile = escapePath(overlay.textFile());
            parts.add("drawtext=fontfile=" + font
                    + ":textfile=" + textFile
                    + ":fontsize=h/16"
                    + ":fontcolor=white"
                    + ":borderw=2"
                    + ":bordercolor=black"
                    + ":shadowx=1"
                    + ":shadowy=1"
                    + ":shadowcolor=black@0.55"
                    + ":x=(w-text_w)/2"
                    + ":y=h-text_h-h/14"
                    + ":expansion=none"
                    + ":enable=between(t\\,"
                    + String.format(Locale.US, "%.3f", overlay.startSec())
                    + "\\,"
                    + String.format(Locale.US, "%.3f", overlay.endSec())
                    + ")");
        }
        return String.join(",", parts);
    }

    static String escapePath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        normalized = normalized.replace("'", "\\'");
        normalized = normalized.replace(":", "\\:");
        return "'" + normalized + "'";
    }
}
