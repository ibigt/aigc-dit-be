package cn.aigc.dit.server.workflow;

import java.util.List;
import java.util.Map;

public interface WorkflowFilmComposer {

    record Clip(String storageKey, double trimStart, double trimEnd, boolean muted, double volume) {
    }

    /**
     * 音频轨片段（剪辑台 A1 混入成片）：start 为成片内混入位置（秒，来自时间线绝对位置），
     * 经 atrim 裁剪 + volume 后 adelay 对齐，amix 到视频拼接结果上。
     */
    record AudioClip(String storageKey, double start, double trimStart, double trimEnd, boolean muted, double volume) {
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

    /**
     * 片段视觉样式（排期 #7 烧录）：调节 / 特效映射为该路输入的 ffmpeg 视频滤镜；
     * 转场施加在本片段与右邻之间（xfade）。字段缺省（null / 0）= 无该效果，
     * 全部缺省时 filter 输出与旧版逐字节一致。
     */
    record ClipStyle(Integer brightness, Integer contrast, Integer saturate,
                     String effect, String transitionId, Double transitionSeconds) {

        public static ClipStyle NONE = new ClipStyle(null, null, null, null, null, null);

        public boolean isEmpty() {
            return (brightness == null || brightness == 0)
                    && (contrast == null || contrast == 0)
                    && (saturate == null || saturate == 0)
                    && effect == null && transitionId == null;
        }
    }

    /** 叠加层片段（文字 / 图形轨）：成片内时间轴区间 + 渲染参数，Java2D 烧成 PNG 后 overlay。 */
    record OverlayClip(double start, double end, String kind, String text,
                       String color, double fontSize, int weight, String align,
                       String shape, double x, double y, double scale, double opacity) {
    }

    /**
     * V2 画中画叠加（副视频轨）：sourceKey 为已解析的产物 storageKey（或本地导入素材 key），
     * x/y 为画面百分比位置（默认 70/25），scale 为画面百分比宽（默认 30），
     * trim 为源内裁剪区间。opacity 0-100。
     */
    record PipClip(double start, double end, String sourceKey,
                   double trimStart, double trimEnd,
                   double x, double y, double scale, double opacity, boolean muted) {
    }

    FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target);

    FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target);

    /** 带音频轨的合成：audioClips 为空时与无音轨行为完全一致。 */
    default FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                               List<AudioClip> audioClips) {
        return compose(userId, runId, nodeId, clips, target);
    }

    /** 带音频轨的复用探测：audioClips 参与指纹，音轨变更不复用旧成片。 */
    default FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                                     List<AudioClip> audioClips) {
        return findExisting(userId, runId, nodeId, clips, target);
    }

    /**
     * 完整合成（排期 #7）：clips 带视觉样式、overlays 为文字/图形烧录层、audioClips 为 A1 混音。
     * 任一为空/缺省时与低阶重载行为完全一致（filter 逐字节不变）。
     */
    default FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                               List<AudioClip> audioClips, List<ClipStyle> styles, List<OverlayClip> overlays) {
        return compose(userId, runId, nodeId, clips, target, audioClips, styles, overlays, List.of());
    }

    /** V2 画中画完整合成：pipClips 为空时与四参重载逐字节一致。 */
    default FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                               List<AudioClip> audioClips, List<ClipStyle> styles, List<OverlayClip> overlays,
                               List<PipClip> pipClips) {
        return compose(userId, runId, nodeId, clips, target, audioClips, styles, overlays);
    }

    /** 完整复用探测：styles / overlays 同样参与指纹，样式或叠加层变更触发重合成。 */
    default FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                                     List<AudioClip> audioClips, List<ClipStyle> styles, List<OverlayClip> overlays) {
        return findExisting(userId, runId, nodeId, clips, target, audioClips, styles, overlays, List.of());
    }

    /** V2 画中画复用探测：pipClips 同样参与指纹。 */
    default FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                                     List<AudioClip> audioClips, List<ClipStyle> styles, List<OverlayClip> overlays,
                                     List<PipClip> pipClips) {
        return findExisting(userId, runId, nodeId, clips, target, audioClips, styles, overlays);
    }

    /** 兼容旧调用：无目标尺寸（历史默认 1280x720）。 */
    default FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips) {
        return compose(userId, runId, nodeId, clips, null);
    }

    /** 兼容旧调用：无目标尺寸。 */
    default FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips) {
        return findExisting(userId, runId, nodeId, clips, null);
    }
}
