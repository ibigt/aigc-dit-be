package cn.aigc.dit.generate.mock;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Mock 成片：优先使用原型演示图/视频，缺失时再回退到程序绘制 PNG。
 */
@Slf4j
final class MockMediaFactory {

    private static final Sample[] IMAGES = {
            new Sample("mock-media/nano-banana-pro.webp", "image/webp", ".webp", null),
            new Sample("mock-media/qwen-image-edit.webp", "image/webp", ".webp", null)
    };
    private static final Sample[] VIDEOS = {
            new Sample("mock-media/comfy-racer.webm", "video/webm", ".webm", "mock-media/comfy-racer-poster.webp"),
            new Sample("mock-media/grok-video.webm", "video/webm", ".webm", "mock-media/grok-video-poster.webp")
    };

    private MockMediaFactory() {
    }

    static RenderedObject render(GenerateRequest request, int copyIndex) {
        if (ModelCategory.AUDIO.equalsIgnoreCase(request.getCategory())) {
            return new RenderedObject(renderWav(copyIndex), "audio/wav", ".wav", null);
        }
        boolean video = ModelCategory.VIDEO.equalsIgnoreCase(request.getCategory());
        Sample[] pool = video ? VIDEOS : IMAGES;
        Sample sample = pool[Math.floorMod(copyIndex - 1, pool.length)];
        byte[] bytes = loadResource(sample.resource());
        if (bytes != null && bytes.length > 0) {
            byte[] poster = sample.posterResource() == null ? null : loadResource(sample.posterResource());
            return new RenderedObject(bytes, sample.contentType(), sample.extension(), poster);
        }
        int[] size = canvasSize(request.getRatio(), request.getResolution(), request.getCategory());
        byte[] png = drawPng(size[0], size[1], request, copyIndex);
        if (video && FfmpegSupport.available()) {
            byte[] mp4 = toMp4(png, request.getDurationSec() == null ? 4 : request.getDurationSec());
            if (mp4 != null) {
                return new RenderedObject(mp4, "video/mp4", ".mp4", null);
            }
        }
        return new RenderedObject(png, "image/png", ".png", null);
    }

    private static byte[] renderWav(int copyIndex) {
        int sampleRate = 16_000;
        int durationMs = 700;
        int sampleCount = sampleRate * durationMs / 1000;
        int dataSize = sampleCount * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataSize);
        wav.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataSize);
        double frequency = 360 + Math.floorMod(copyIndex, 4) * 80;
        for (int i = 0; i < sampleCount; i++) {
            double fade = Math.min(1.0, Math.min(i / 800.0, (sampleCount - i) / 800.0));
            short sample = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * 6000 * fade);
            wav.putShort(sample);
        }
        return wav.array();
    }

    private static byte[] loadResource(String path) {
        try (InputStream in = MockMediaFactory.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("load mock media failed: {}", path);
            return null;
        }
    }

    static byte[] drawPng(int width, int height, GenerateRequest request, int copyIndex) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color bg = palette(copyIndex);
            g.setColor(bg);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(255, 255, 255, 40));
            g.fillRoundRect(width / 10, height / 6, width * 8 / 10, height * 2 / 3, 48, 48);
            g.setColor(Color.WHITE);
            int titleSize = Math.max(22, width / 28);
            g.setFont(new Font("SansSerif", Font.BOLD, titleSize));
            String title = "Mock · " + (request.getMode() == null ? "" : request.getMode().toUpperCase(Locale.ROOT));
            g.drawString(title, width / 9, height / 3);
            g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(16, width / 42)));
            String prompt = request.getPrompt() == null ? "" : request.getPrompt();
            if (prompt.length() > 36) {
                prompt = prompt.substring(0, 36) + "…";
            }
            g.drawString(prompt, width / 9, height / 3 + titleSize + 18);
            g.drawString((request.getRatio() == null ? "1:1" : request.getRatio())
                    + "  " + (request.getResolution() == null ? "" : request.getResolution())
                    + "  copy " + copyIndex, width / 9, height / 3 + titleSize * 2 + 28);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] toMp4(byte[] png, int durationSec) {
        Path pngFile = null;
        Path mp4File = null;
        try {
            pngFile = FfmpegSupport.tempFile("dit-mock-", ".png");
            mp4File = FfmpegSupport.tempFile("dit-mock-", ".mp4");
            Files.write(pngFile, png);
            int seconds = Math.min(15, Math.max(2, durationSec));
            FfmpegSupport.run(List.of(
                    "-loop", "1",
                    "-t", String.valueOf(seconds),
                    "-i", pngFile.toString(),
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-r", "24",
                    mp4File.toString()));
            return Files.readAllBytes(mp4File);
        } catch (Exception e) {
            log.warn("mock video render fallback to png: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(pngFile);
            deleteQuietly(mp4File);
        }
    }

    static int[] canvasSize(String ratio, String resolution, String category) {
        int longEdge = longEdge(resolution, category);
        int[] parts = parseRatio(ratio);
        int rw = parts[0];
        int rh = parts[1];
        if (rw >= rh) {
            return new int[]{longEdge, Math.max(2, longEdge * rh / rw)};
        }
        return new int[]{Math.max(2, longEdge * rw / rh), longEdge};
    }

    private static int longEdge(String resolution, String category) {
        String res = resolution == null ? "" : resolution.toUpperCase(Locale.ROOT);
        if (res.contains("4K")) {
            return 1024;
        }
        if (res.contains("2K") || res.contains("1080")) {
            return 768;
        }
        if (res.contains("720")) {
            return 640;
        }
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return 640;
        }
        return 512;
    }

    private static int[] parseRatio(String ratio) {
        if (ratio == null || "自动".equals(ratio) || !ratio.contains(":")) {
            return new int[]{16, 9};
        }
        String[] parts = ratio.split(":");
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (NumberFormatException ignored) {
            // fallback
        }
        return new int[]{16, 9};
    }

    private static Color palette(int copyIndex) {
        Color[] colors = {
                new Color(28, 48, 84),
                new Color(64, 32, 72),
                new Color(22, 70, 64),
                new Color(92, 42, 28)
        };
        return colors[Math.floorMod(copyIndex - 1, colors.length)];
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // temp cleanup
        }
    }

    record Sample(String resource, String contentType, String extension, String posterResource) {
    }

    record RenderedObject(byte[] bytes, String contentType, String extension, byte[] posterBytes) {
    }
}
