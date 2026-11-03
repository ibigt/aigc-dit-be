package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 成片衔接。直拼整段保留；顺接按相邻两镜决定裁切，再交给 ffmpeg。
 * 同场顺切裁气口，换章、换地点或新角色只去掉定格。画面仍是硬切。
 */
public final class DramaFilmModes {

    public static final String DIRECT = "直拼";
    public static final String SMOOTH = "顺接";

    /** 同场顺切，接点两侧各裁。 */
    public static final double MATCH_TRIM_SEC = 0.20;
    /** 换场，只去掉生成片头尾的静止帧。 */
    public static final double SCENE_TRIM_SEC = 0.08;
    /** 裁完短于此时长则这镜整段保留。 */
    public static final double MIN_KEEP_SEC = 1.2;
    /** 接点两侧各淡这么多，合计约 60 毫秒，且不缩短音画时长。 */
    public static final double AUDIO_EDGE_FADE_SEC = 0.03;

    private DramaFilmModes() {
    }

    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return SMOOTH;
        }
        String value = raw.trim();
        if (DIRECT.equals(value) || "direct".equalsIgnoreCase(value) || "copy".equalsIgnoreCase(value)) {
            return DIRECT;
        }
        if (SMOOTH.equals(value) || "smooth".equalsIgnoreCase(value) || "join".equalsIgnoreCase(value)) {
            return SMOOTH;
        }
        return SMOOTH;
    }

    public static boolean direct(String mode) {
        return DIRECT.equals(normalize(mode));
    }

    /**
     * 对白在源片上的时间窗。裁切不得进入该区间。
     */
    public record CueWindow(double startSec, double endSec) {
    }

    /**
     * 单镜在成片时间轴上的裁切。{@code headSec} 从片头去掉，{@code tailSec} 从片尾去掉。
     */
    public record ClipSpan(double headSec, double tailSec, double durationSec) {

        public double endSec() {
            return Math.max(headSec, durationSec - tailSec);
        }

        public double keptSec() {
            return Math.max(0D, endSec() - headSec);
        }
    }

    /** 成片可见时长：各镜裁完后保留的秒数，四舍五入到秒。 */
    public static int totalKeptSec(List<ClipSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        double total = 0D;
        for (ClipSpan span : spans) {
            if (span != null) {
                total += Math.max(0D, span.keptSec());
            }
        }
        return (int) Math.round(total);
    }

    public static double durationOf(DramaShot shot, Double probed) {
        if (probed != null && Double.isFinite(probed) && probed >= 1D) {
            return probed;
        }
        if (shot != null && shot.getDurationSec() != null && shot.getDurationSec() >= 1) {
            return shot.getDurationSec();
        }
        return DramaContract.DEFAULT_DURATION_SEC;
    }

    /**
     * @param durations 与镜头等长；缺项或探测失败时回退到分镜时长
     * @param cues      与镜头等长的对白窗；空表示没有字幕，用固定秒数裁
     */
    public static List<ClipSpan> plan(String mode, List<DramaShot> shots, List<Double> durations,
                                       List<List<CueWindow>> cues) {
        if (shots == null || shots.isEmpty()) {
            return List.of();
        }
        int count = shots.size();
        double[] heads = new double[count];
        double[] tails = new double[count];
        double[] length = new double[count];
        for (int i = 0; i < count; i++) {
            Double probed = durations != null && i < durations.size() ? durations.get(i) : null;
            length[i] = durationOf(shots.get(i), probed);
        }
        if (!direct(mode) && count > 1) {
            for (int i = 0; i < count - 1; i++) {
                double cut = joinCut(shots.get(i), shots.get(i + 1));
                tails[i] = cut;
                heads[i + 1] = cut;
            }
            for (int i = 0; i < count; i++) {
                clampToSpeech(heads, tails, i, length[i], cues);
                if (keptMillis(length[i], heads[i], tails[i]) < Math.round(MIN_KEEP_SEC * 1000D)) {
                    heads[i] = 0D;
                    tails[i] = 0D;
                }
            }
        }
        List<ClipSpan> spans = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            spans.add(new ClipSpan(roundMillis(heads[i]), roundMillis(tails[i]), roundMillis(length[i])));
        }
        return spans;
    }

    /**
     * 同章、景别可接、没有新角色，且地点相同，才按同场顺切裁气口。
     * 换章、换地点或新角色入画只去掉约 0.08 秒定格。
     */
    static double joinCut(DramaShot previous, DramaShot next) {
        if (DramaShotScales.chainable(previous, next) && samePlace(previous, next)) {
            return MATCH_TRIM_SEC;
        }
        return SCENE_TRIM_SEC;
    }

    private static void clampToSpeech(double[] heads, double[] tails, int index, double duration,
                                       List<List<CueWindow>> cues) {
        List<CueWindow> windows = cues != null && index < cues.size() ? cues.get(index) : null;
        if (windows == null || windows.isEmpty() || duration <= 0D) {
            heads[index] = Math.min(heads[index], duration);
            tails[index] = Math.min(tails[index], Math.max(0D, duration - heads[index]));
            return;
        }
        double speechStart = Double.POSITIVE_INFINITY;
        double speechEnd = Double.NEGATIVE_INFINITY;
        for (CueWindow window : windows) {
            if (window == null || window.endSec() <= window.startSec() || window.startSec() >= duration) {
                continue;
            }
            speechStart = Math.min(speechStart, Math.max(0D, window.startSec()));
            speechEnd = Math.max(speechEnd, Math.min(duration, window.endSec()));
        }
        if (speechStart >= speechEnd || speechStart == Double.POSITIVE_INFINITY) {
            heads[index] = Math.min(heads[index], duration);
            tails[index] = Math.min(tails[index], Math.max(0D, duration - heads[index]));
            return;
        }
        heads[index] = Math.min(heads[index], speechStart);
        tails[index] = Math.min(tails[index], Math.max(0D, duration - speechEnd));
        if (heads[index] + tails[index] > duration) {
            tails[index] = Math.max(0D, duration - heads[index]);
        }
    }

    private static boolean samePlace(DramaShot previous, DramaShot next) {
        return place(previous).equals(place(next));
    }

    private static String place(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getLocationName())) {
            return "";
        }
        return shot.getLocationName().trim();
    }

    private static long keptMillis(double duration, double head, double tail) {
        return Math.round((duration - head - tail) * 1000D);
    }

    private static double roundMillis(double value) {
        return Math.round(Math.max(0D, value) * 1000D) / 1000D;
    }
}
