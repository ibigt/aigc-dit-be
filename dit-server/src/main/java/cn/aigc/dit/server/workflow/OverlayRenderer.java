package cn.aigc.dit.server.workflow;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 剪辑台叠加层烧录渲染（排期 #7）：把文字 / 图形片段渲染成整幅透明 PNG，
 * 由 ffmpeg overlay + enable=between(t,…) 叠到成片上。
 *
 * <p>不用 drawtext：环境无 fontconfig 且中文字体不可控；Java2D 用 JVM 内置逻辑字体
 * （Windows/Linux 均能回退到中文字形），渲染结果只依赖几何参数，可单测。
 *
 * <p>几何与前端预览（PlayerStage overlay + editing-suite.css）同口径：
 * 文字 bottom:14%、max-width:88%；图形 x/y 为百分比中心点、rect 18%×18% 等。
 * 预览是 HTML 渲染、烧录是 Java2D，二者是「近似一致」而非像素级一致（验收按此口径）。
 */
final class OverlayRenderer {

    private OverlayRenderer() {
    }

    /** 一条叠加：kind = text / graphic，start/end 为成片内时间轴区间（秒）。 */
    record Overlay(String kind, double start, double end, String text,
                   String color, double fontSize, int weight, String align,
                   String shape, double x, double y, double scale, double opacity) {
    }

    /** 与前端「文字样式」面板输入范围（min=8 / max=120）对齐的字号上下限，单位仍是预览基准 CSS px。 */
    static final double MIN_FONT_SIZE = 8;
    static final double MAX_FONT_SIZE = 120;

    /**
     * 预览基准宽度：前端 .ce-monitor 宽 min(78%, 560px)，字号预设（默认 16 / 标题 32 /
     * 字幕 18 / 注释 13）与用户手填字号都是**这个预览器上的 CSS px**。烧录到 1920 宽成片时
     * 必须按「成片宽 ÷ 预览基准宽」等比放大，否则 16px 的字在成片上只有预览里的 1/3.4 大小
     * ——用户看到「字幕字号和预览不一致」。基准取 560px（预览器上限宽度）。
     *
     * <p>预览器实际宽度会随容器高度收缩，这里取设计基准值做等比换算，保证「字宽 / 画幅宽」
     * 的视觉占比一致，而非追求与某一时刻的预览器像素级相等。
     */
    static final double PREVIEW_BASE_WIDTH = 560;

    /** 缺省字号（与前端 Number(style.fontSize) || 16 一致）。 */
    static final double DEFAULT_FONT_SIZE = 16;

    /**
     * 字号换算：预览 CSS px → 成片实际 px（按宽度等比）。上下限先在预览基准上钳制，
     * 再随画幅一起放大，保证任意分辨率下视觉占比一致，也防止巨型文字打爆画布。
     */
    static double scaledFontSize(double previewPx, int width) {
        double base = clamp(previewPx <= 0 ? DEFAULT_FONT_SIZE : previewPx, MIN_FONT_SIZE, MAX_FONT_SIZE);
        return Math.max(1, base * width / PREVIEW_BASE_WIDTH);
    }

    static byte[] renderPng(List<Overlay> overlays, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (Overlay overlay : overlays) {
                if (overlay == null || overlay.opacity() <= 0) continue;
                if ("text".equals(overlay.kind())) {
                    drawText(g, overlay, width, height);
                } else if ("graphic".equals(overlay.kind())) {
                    drawGraphic(g, overlay, width, height);
                }
            }
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("overlay png encode failed", e);
        }
    }

    static Path writeTempPng(List<Overlay> overlays, int width, int height, Path target) {
        try {
            Files.write(target, renderPng(overlays, width, height));
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("overlay png write failed: " + target, e);
        }
    }

    // ── 文字：bottom 14%，max-width 88%，pre-wrap 逐行绘制，带黑色描边保证可读性 ──

    private static void drawText(Graphics2D g, Overlay overlay, int width, int height) {
        String text = overlay.text() == null ? "" : overlay.text();
        if (text.isBlank()) return;
        // 字号按成片宽度等比放大（预览 CSS px → 成片 px），与前端预览同口径
        double size = scaledFontSize(overlay.fontSize(), width);
        int style = overlay.weight() >= 700 ? Font.BOLD : Font.PLAIN;
        Font font = new Font(Font.SANS_SERIF, style, (int) Math.round(size));
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics(font);
        double maxWidth = width * 0.88;
        List<String> lines = wrap(text, metrics, maxWidth);
        int lineHeight = (int) Math.round(metrics.getHeight() * 1.35);
        int blockHeight = lines.size() * lineHeight;
        int bottom = (int) Math.round(height * 0.14);
        int top = Math.max(0, height - bottom - blockHeight);
        Color color = parseColor(overlay.color(), Color.WHITE);
        int margin = (int) Math.round(width * 0.06);
        String align = overlay.align() == null ? "center" : overlay.align();
        int y = top + metrics.getAscent();
        for (String line : lines) {
            int lineWidth = metrics.stringWidth(line);
            int x = switch (align) {
                case "left" -> margin;
                case "right" -> width - margin - lineWidth;
                default -> (width - lineWidth) / 2;
            };
            g.setStroke(new BasicStroke(Math.max(1f, (float) (size / 14)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, (int) (255 * 0.65)));
            int paintX = x;
            int paintY = y;
            g.drawGlyphVector(g.getFont().createGlyphVector(g.getFontRenderContext(), line), paintX, paintY);
            g.setColor(color);
            g.drawString(line, paintX, paintY);
            y += lineHeight;
        }
    }

    private static List<String> wrap(String text, FontMetrics metrics, double maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\n", -1)) {
            if (metrics.stringWidth(rawLine) <= maxWidth) {
                lines.add(rawLine);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < rawLine.length(); i++) {
                char c = rawLine.charAt(i);
                if (Character.isWhitespace(c) || isCjk(c)) {
                    if (metrics.stringWidth(current + String.valueOf(c)) > maxWidth && current.length() > 0) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(c);
                } else {
                    current.append(c);
                    if (metrics.stringWidth(current.toString()) > maxWidth) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    // ── 图形：x/y 百分比中心点，形状尺寸与前端 ce-ov-shape-* 同比例，支持旋转 ──

    private static void drawGraphic(Graphics2D g, Overlay overlay, int width, int height) {
        String shape = overlay.shape() == null ? "rect" : overlay.shape();
        double scale = clamp(overlay.scale() <= 0 ? 1 : overlay.scale(), 0.05, 8);
        float alpha = (float) clamp(overlay.opacity(), 0, 1);
        Color color = new Color(parseColor(overlay.color(), new Color(0x6c, 0xb2, 0xec)).getRGB(), true);
        color = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * alpha));
        double cx = width * clamp(overlay.x(), 0, 100) / 100.0;
        double cy = height * clamp(overlay.y(), 0, 100) / 100.0;
        g.setColor(color);
        switch (shape) {
            case "circle" -> {
                double r = width * 0.08 * scale;
                g.fillOval(round(cx - r), round(cy - r), round(r * 2), round(r * 2));
            }
            case "triangle" -> {
                // 前端 border 三角：宽 72px 高 62px（@预览基准），按等比换算
                double w = width * 0.10 * scale;
                double h = w * 62.0 / 72.0;
                Path2D path = new Path2D.Double();
                path.moveTo(cx - w / 2, cy + h / 2);
                path.lineTo(cx + w / 2, cy + h / 2);
                path.lineTo(cx, cy - h / 2);
                path.closePath();
                g.fill(path);
            }
            case "arrow" -> {
                double w = width * 0.078 * scale;
                double h = w * 44.0 / 56.0;
                Path2D path = new Path2D.Double();
                path.moveTo(cx - w / 2, cy - h / 2);
                path.lineTo(cx + w / 2, cy);
                path.lineTo(cx - w / 2, cy + h / 2);
                path.closePath();
                g.fill(path);
            }
            case "star" -> {
                double r = width * 0.042 * scale;
                Path2D path = new Path2D.Double();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    double radius = i % 2 == 0 ? r : r * 0.42;
                    double px = cx + Math.cos(angle) * radius;
                    double py = cy + Math.sin(angle) * radius;
                    if (i == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                path.closePath();
                g.fill(path);
            }
            case "line" -> {
                double w = width * 0.40 * scale;
                float thickness = Math.max(2f, (float) (height * 0.005 * scale));
                g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(round(cx - w / 2), round(cy), round(cx + w / 2), round(cy));
            }
            default -> {
                // rect：18% × 18%
                double w = width * 0.18 * scale;
                double h = height * 0.18 * scale;
                g.fillRect(round(cx - w / 2), round(cy - h / 2), round(w), round(h));
            }
        }
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return fallback;
        try {
            return new Color(Integer.parseInt(hex.substring(1), 16));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    /** 供需要旋转的形状使用（当前预设无旋转字段，保留扩展位）。 */
    private static AffineTransform rotationAt(double cx, double cy, double degrees) {
        AffineTransform transform = AffineTransform.getTranslateInstance(cx, cy);
        transform.rotate(Math.toRadians(degrees));
        transform.translate(-cx, -cy);
        return transform;
    }
}
