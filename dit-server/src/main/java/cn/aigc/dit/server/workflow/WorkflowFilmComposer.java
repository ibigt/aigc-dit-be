package cn.aigc.dit.server.workflow;

import java.util.List;
import java.util.Map;

public interface WorkflowFilmComposer {

    record Clip(String storageKey, double trimStart, double trimEnd, boolean muted, double volume) {
    }

    /**
     * 成片目标尺寸（白名单制，防任意尺寸注入 FFmpeg）。
     * 画幅×清晰度组合见 {@link #SUPPORTED_SIZES}；null 表示沿用历史默认 1280x720。
     * 2.35:1 的 1080p 高度取 816（1920/2.35=817.02，yuv420p 要求偶数，向下取偶）。
     */
    record FilmSize(int width, int height) {

        public static FilmSize of(int width, int height) {
            FilmSize size = new FilmSize(width, height);
            if (!SUPPORTED_SIZES.containsValue(size)) {
                throw new IllegalArgumentException("不支持的成片尺寸：" + width + "x" + height);
            }
            return size;
        }

        /** 由画幅比（16:9 等）与清晰度（1080p/720p）推导白名单尺寸；无法识别时返回 null（走默认 1280x720）。 */
        public static FilmSize of(String ratio, String resolution) {
            if (ratio == null || resolution == null) {
                return null;
            }
            String quality = resolution.trim().toLowerCase().replace("p", "");
            return SUPPORTED_SIZES.get(ratio.trim() + "@" + quality);
        }

        public String text() {
            return width + "x" + height;
        }
    }

    /** 5 画幅 × 2 清晰度白名单（与前端 ASPECTS 表同源，改一处必须同步另一处）。 */
    Map<String, FilmSize> SUPPORTED_SIZES = Map.of(
            "16:9@1080", new FilmSize(1920, 1080),
            "16:9@720", new FilmSize(1280, 720),
            "9:16@1080", new FilmSize(1080, 1920),
            "9:16@720", new FilmSize(720, 1280),
            "1:1@1080", new FilmSize(1080, 1080),
            "1:1@720", new FilmSize(720, 720),
            "4:3@1080", new FilmSize(1440, 1080),
            "4:3@720", new FilmSize(960, 720),
            "2.35:1@1080", new FilmSize(1920, 816),
            "2.35:1@720", new FilmSize(1280, 544));

    record FilmOutput(String storageKey, String posterKey, double durationSeconds, Integer width, Integer height) {

        public FilmOutput(String storageKey, String posterKey, double durationSeconds) {
            this(storageKey, posterKey, durationSeconds, null, null);
        }
    }

    FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target);

    FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target);

    /** 兼容旧调用：无目标尺寸（历史默认 1280x720）。 */
    default FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips) {
        return compose(userId, runId, nodeId, clips, null);
    }

    /** 兼容旧调用：无目标尺寸。 */
    default FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips) {
        return findExisting(userId, runId, nodeId, clips, null);
    }
}
