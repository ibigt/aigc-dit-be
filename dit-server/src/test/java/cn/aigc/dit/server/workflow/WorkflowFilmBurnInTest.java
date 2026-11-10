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
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "mono", null, null), 1920).contains("colorchannelmixer"));
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "vintage", null, null), 1920).contains("sepia=0.55"));
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "cool", null, null), 1920).contains("hue=h=-12"));
        // 未知特效 id 不阻断成片
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "unknown", null, null), 1920));
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(null, 1920));
        assertEquals("", FfmpegWorkflowFilmComposer.effectFilterOf(WorkflowFilmComposer.ClipStyle.NONE, 1920));
    }

    /**
     * 模糊半径必须随成片宽度等比放大（14.51 字号同类问题）：前端预览是 CSS blur() 作用在
     * ≤560px 预览器上，sigma 固定 2 画到 1920 宽成片上模糊强度只有预览的 ~1/3.4。
     */
    @Test
    void blurSigmaScalesWithOutputWidth() {
        // 1920 宽（1080P 横屏）：2 * 1920/560 ≈ 6.86
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "blur", null, null), 1920).contains("gblur=sigma=6.86"),
                "1920 宽成片 sigma 应约 6.86");
        // 1280 宽（720P）：2 * 1280/560 ≈ 4.57
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "blur", null, null), 1280).contains("gblur=sigma=4.57"));
        // 预览基准宽度上保持原值
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "blur", null, null), 560).contains("gblur=sigma=2.00"));
        // 柔光的轻微模糊同口径放大：0.5 * 1920/560 ≈ 1.71
        assertTrue(FfmpegWorkflowFilmComposer.effectFilterOf(
                new WorkflowFilmComposer.ClipStyle(0, 0, 0, "soft", null, null), 1920).contains("gblur=sigma=1.71"));
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

    /**
     * 字号必须按成片宽度等比放大：前端字号预设是**预览器（.ce-monitor 宽 560px）上的 CSS px**，
     * 直接把 16 画在 1920 宽成片上会小到预览的 1/3.4（用户报「字幕字号和预览不一致」）。
     */
    @Test
    void fontSizeScalesWithOutputWidth() {
        // 预览基准 560px 上的 16px → 1920 宽成片应为 16 * 1920/560 ≈ 54.9
        assertEquals(55L, Math.round(OverlayRenderer.scaledFontSize(16, 1920)));
        // 720p 成片：16 * 1280/560 ≈ 36.6
        assertEquals(37L, Math.round(OverlayRenderer.scaledFontSize(16, 1280)));
        // 竖屏 1080 宽（画幅 9:16 时 width 也是 1080）
        assertEquals(31L, Math.round(OverlayRenderer.scaledFontSize(16, 1080)));
        // 预览基准宽度上保持原值，不放大也不缩小
        assertEquals(16L, Math.round(OverlayRenderer.scaledFontSize(16, 560)));
        // 缺省字号按 16 处理
        assertEquals(55L, Math.round(OverlayRenderer.scaledFontSize(0, 1920)));
        // 标题预设 32px 也同比放大
        assertEquals(110L, Math.round(OverlayRenderer.scaledFontSize(32, 1920)));
        // 上下限在预览基准上钳制后再放大，与前端 input min=8 / max=120 同口径
        assertEquals(Math.round(8 * 1920 / 560.0), Math.round(OverlayRenderer.scaledFontSize(1, 1920)));
        assertEquals(Math.round(120 * 1920 / 560.0), Math.round(OverlayRenderer.scaledFontSize(999, 1920)));
    }

    /** 真实 PNG 上的文字像素高度也必须随画幅放大，而不只是纯函数返回了更大的数。 */
    @Test
    void renderedTextHeightScalesWithOutputWidth() throws Exception {
        OverlayRenderer.Overlay text = new OverlayRenderer.Overlay(
                "text", 0, 2, "字幕", "#ffffff", 32, 700, "center", null, 0, 0, 0, 100);
        int small = textPixelHeight(OverlayRenderer.renderPng(List.of(text), 560, 315));
        int large = textPixelHeight(OverlayRenderer.renderPng(List.of(text), 1120, 630));
        assertTrue(small > 8, "预览基准画幅应能渲染出可见文字，实际=" + small);
        assertTrue(large >= small * 2 - 4 && large <= small * 2 + 4,
                "画幅宽度翻倍时文字像素高度应约翻倍，small=" + small + " large=" + large);
    }

    private static int textPixelHeight(byte[] png) throws Exception {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertNotNull(image);
        int minY = image.getHeight();
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxY < 0 ? 0 : maxY - minY + 1;
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
