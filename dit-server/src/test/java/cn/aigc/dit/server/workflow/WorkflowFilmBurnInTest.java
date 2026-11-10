package cn.aigc.dit.server.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排期 #7：成片烧录（调节 / 特效 / 转场 / 文字图形叠加层）。
 * 关键红线：没有任何样式与叠加时，filter 必须与旧版逐字节一致。
 */
class WorkflowFilmBurnInTest {

    private static final WorkflowFilmComposer.Clip CLIP = new WorkflowFilmComposer.Clip("v.mp4", 0, 5, false, 1);

    private static List<String> argsWithStyles(List<WorkflowFilmComposer.ClipStyle> styles) {
        return FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of(), styles, null, List.of());
    }

    @Test
    void noStylesKeepsFilterByteIdenticalToLegacy() {
        List<String> legacy = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of());
        List<String> withEmptyStyles = argsWithStyles(List.of(WorkflowFilmComposer.ClipStyle.NONE));

        assertEquals(String.join(" ", legacy), String.join(" ", withEmptyStyles),
                "无样式时 filter 必须与旧版逐字节一致");
        assertFalse(String.join(" ", legacy).contains("eq="));
        assertFalse(String.join(" ", legacy).contains("xfade"));
        assertFalse(String.join(" ", legacy).contains("overlay"));
    }

    @Test
    void nullStylesAlsoKeepsLegacyFilter() {
        List<String> legacy = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of());
        List<String> withNull = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of(), null, null, List.of());
        assertEquals(String.join(" ", legacy), String.join(" ", withNull));
    }

    @Test
    void adjustBecomesEqFilter() {
        List<String> args = argsWithStyles(List.of(
                new WorkflowFilmComposer.ClipStyle(20, 10, -10, null, null, null)));
        String command = String.join(" ", args);
        assertTrue(command.contains("eq=brightness=0.100:contrast=1.100:saturation=0.900"), command);
        // 滤镜插在 pad 之后、setsar 之前
        assertTrue(command.indexOf("eq=") < command.indexOf("setsar=1"), command);
    }

    @Test
    void effectsMapToFfmpegFilters() {
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "mono", null, null)).contains("colorchannelmixer"));
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "vintage", null, null)).contains("sepia=0.55"));
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "blur", null, null)).contains("gblur=sigma=2"));
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "cool", null, null)).contains("hue=h=-12"));
        // 未知特效 id 不阻断成片
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "unknown", null, null)));
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(null));
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(WorkflowFilmComposer.ClipStyle.NONE));
    }

    @Test
    void transitionMapsToXfadeChain() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("a.mp4"), Path.of("b.mp4")),
                List.of(CLIP, new WorkflowFilmComposer.Clip("b.mp4", 0, 5, false, 1)),
                List.of(true, true), null, Path.of("out.mp4"),
                List.of(), List.of(),
                List.of(new WorkflowFilmComposer.ClipStyle(0, 0, 0, null, "fade", 0.6),
                        WorkflowFilmComposer.ClipStyle.NONE),
                null, List.of());
        String command = String.join(" ", args);
        assertTrue(command.contains("xfade=transition=fade:duration=0.600:offset=4.400"), command);
        assertTrue(command.contains("[vout]"), command);
        assertTrue(command.contains("-map [vout]"), command);
    }

    @Test
    void transitionPresetIdsMapToXfadeNames() {
        assertEquals("fade", FfmpegWorkflowFilmComposer.xfadeTransitionOf("fade"));
        assertEquals("slideleft", FfmpegWorkflowFilmComposer.xfadeTransitionOf("slide"));
        assertEquals("zoomin", FfmpegWorkflowFilmComposer.xfadeTransitionOf("zoom"));
        assertEquals("wipeleft", FfmpegWorkflowFilmComposer.xfadeTransitionOf("wipe"));
        assertEquals("fadewhite", FfmpegWorkflowFilmComposer.xfadeTransitionOf("flash"));
        assertEquals("fade", FfmpegWorkflowFilmComposer.xfadeTransitionOf("unknown"));
        assertEquals("fade", FfmpegWorkflowFilmComposer.xfadeTransitionOf(null));
    }

    @Test
    void overlayAddsEnableBetweenOnOverlayChain() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of(), null, Path.of("overlay.png"),
                List.of(new WorkflowFilmComposer.OverlayClip(1, 3, "text", "你好", "#ffffff", 16, 400, "center",
                        null, 0, 0, 0, 0)));
        String command = String.join(" ", args);
        assertTrue(command.contains("overlay=enable='between(t,1.000,3.000)'"), command);
        assertTrue(command.contains("-map [ov0]"), command);
    }

    /**
     * ffmpeg overlay 滤镜语义：第一个输入是主画面，第二个是覆盖层。
     * 写成 [png:v][v] 会把视频叠到透明 PNG 上——画面全黑、字幕出不来。
     */
    @Test
    void overlayKeepsMainVideoAsFirstInput() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of(), null, Path.of("overlay.png"),
                List.of(new WorkflowFilmComposer.OverlayClip(1, 3, "text", "你好", "#ffffff", 16, 400, "center",
                        null, 0, 0, 0, 0)));
        String command = String.join(" ", args);
        assertTrue(command.contains("[v][1:v]overlay="), "主画面必须在前：" + command);
        assertFalse(command.contains("[1:v][v]overlay="), "覆盖层不能在前：" + command);
    }

    /**
     * 音轨 amix 分支结尾的 [aout] 后面没有分号，直接接 overlay 链会拼成
     * 「[aout][v][1:v]overlay=…」一句，ffmpeg 报 Trailing garbage after a filter（14.48 实测）。
     * 拼接 overlay 链前必须确保前文以 "; " 收尾。
     */
    @Test
    void overlayChainAfterAmixMustBeSeparatedBySemicolon() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(new WorkflowFilmComposer.AudioClip("a.mp3", 0, 0, 3, false, 1)),
                List.of(Path.of("a.mp3")), null, Path.of("overlay.png"),
                List.of(new WorkflowFilmComposer.OverlayClip(0, 5, "text", "字幕", "#ffffff", 16, 400, "center",
                        null, 0, 0, 0, 0)));
        String command = String.join(" ", args);
        assertTrue(command.contains("amix=inputs=2"), "前提：有音轨 amix 分支：" + command);
        // [aout] 之后必须紧跟分号，再接 overlay 链；连写 [aout][v] 会解析失败
        assertTrue(command.contains("[aout]; ") && command.contains("overlay="),
                "amix 结尾与 overlay 链之间必须有 '; ' 分隔：" + command);
        assertFalse(command.contains("[aout][v]"), "amix 输出不能与视频流连写成一句：" + command);
    }

    /**
     * 多段叠加必须各用一张 PNG：挤在同一张图里时各段的时间窗会互相串
     * （同一张图里所有文字都在，只是按不同 enable 区间显示）。
     */
    @Test
    void multipleOverlaysUseSeparatePngInputs() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")), List.of(CLIP), List.of(true), null, Path.of("out.mp4"),
                List.of(), List.of(), null, null,
                List.of(new WorkflowFilmComposer.OverlayClip(0, 2, "text", "甲", "#ffffff", 16, 400, "center",
                        null, 0, 0, 0, 0),
                        new WorkflowFilmComposer.OverlayClip(3, 5, "text", "乙", "#ffffff", 16, 400, "center",
                                null, 0, 0, 0, 0)),
                List.of(Path.of("a.png"), Path.of("b.png")));
        String command = String.join(" ", args);
        assertTrue(command.contains("[v][1:v]overlay=enable='between(t,0.000,2.000)'[ov0]"), command);
        assertTrue(command.contains("[ov0][2:v]overlay=enable='between(t,3.000,5.000)'[ov1]"), command);
        assertTrue(command.contains("-map [ov1]"), command);
        // 两张 PNG 各占一个输入
        assertTrue(command.contains("a.png"), command);
        assertTrue(command.contains("b.png"), command);
    }

    @Test
    void overlayRendererProducesPngWithExpectedSize() {
        byte[] png = OverlayRenderer.renderPng(
                List.of(new OverlayRenderer.Overlay("text", 0, 2, "字幕", "#ffffff", 24, 700, "center",
                        null, 0, 0, 0, 100)),
                320, 180);
        assertNotNull(png);
        assertTrue(png.length > 0);
        // PNG 魔数
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
    }

    @Test
    void overlayRendererHandlesGraphicsAndEmptyText() {
        byte[] png = OverlayRenderer.renderPng(
                List.of(new OverlayRenderer.Overlay("graphic", 0, 2, null, "#6cb2ec", 16, 400, "center",
                        "circle", 50, 50, 100, 100),
                        new OverlayRenderer.Overlay("text", 0, 2, "   ", "#ffffff", 16, 400, "center",
                                null, 0, 0, 0, 100)),
                320, 180);
        assertNotNull(png);
        assertTrue(png.length > 0);
    }
}
