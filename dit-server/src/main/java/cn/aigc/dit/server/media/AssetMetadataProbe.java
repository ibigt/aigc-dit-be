package cn.aigc.dit.server.media;

import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 上传素材的宽高与时长探测。探测失败一律返回空值，不阻断上传；
 * 图片优先 {@link ImageIO} 头解析（jpg/png/gif），webp 手写 RIFF 解析；
 * 视频/音频走 ffprobe，缺失时回退 {@link FfmpegSupport#durationSeconds}（仅时长）。
 */
@Slf4j
public final class AssetMetadataProbe {

    private AssetMetadataProbe() {
    }

    public record Metadata(Integer width, Integer height, Long durationMs) {
        public static final Metadata EMPTY = new Metadata(null, null, null);
    }

    public static Metadata probeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Metadata.EMPTY;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new Metadata(reader.getWidth(0), reader.getHeight(0), null);
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.debug("imageio probe failed, fallback to webp riff", e);
        }
        int[] size = webpSize(bytes);
        if (size != null) {
            return new Metadata(size[0], size[1], null);
        }
        return Metadata.EMPTY;
    }

    public static Metadata probeMedia(Path input) {
        if (input == null) {
            return Metadata.EMPTY;
        }
        Integer width = null;
        Integer height = null;
        Double durationSeconds = null;
        try {
            Process process = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1",
                    input.toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                for (String line : text.split("\\R")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    try {
                        if ("width".equals(key)) {
                            width = Integer.valueOf(value);
                        } else if ("height".equals(key)) {
                            height = Integer.valueOf(value);
                        } else if ("duration".equals(key)) {
                            durationSeconds = Double.valueOf(value);
                        }
                    } catch (NumberFormatException ignored) {
                        // keep null
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ffprobe failed key probe, path={}", input, e);
        }
        if (durationSeconds == null) {
            durationSeconds = FfmpegSupport.durationSeconds(input);
        }
        Long durationMs = durationSeconds == null || durationSeconds <= 0
                ? null
                : Math.round(durationSeconds * 1000);
        return new Metadata(width, height, durationMs);
    }

    /**
     * 最小 WEBP RIFF 解析：VP8X（扩展头画布尺寸）/ VP8（有损）/ VP8L（无损）三种 chunk。
     */
    static int[] webpSize(byte[] bytes) {
        if (bytes.length < 16
                || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            return null;
        }
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String chunk = new String(bytes, pos, 4, StandardCharsets.ISO_8859_1);
            int size = (bytes[pos + 4] & 0xFF) | ((bytes[pos + 5] & 0xFF) << 8)
                    | ((bytes[pos + 6] & 0xFF) << 16) | ((bytes[pos + 7] & 0xFF) << 24);
            int data = pos + 8;
            if (size < 0 || data + size > bytes.length) {
                return null;
            }
            int[] result = switch (chunk) {
                case "VP8X" -> size >= 10 ? new int[]{
                        1 + ((bytes[data + 4] & 0xFF) | ((bytes[data + 5] & 0xFF) << 8) | ((bytes[data + 6] & 0xFF) << 16)),
                        1 + ((bytes[data + 7] & 0xFF) | ((bytes[data + 8] & 0xFF) << 8) | ((bytes[data + 9] & 0xFF) << 16))} : null;
                case "VP8 " -> size >= 10 ? lossySize(bytes, data) : null;
                case "VP8L" -> size >= 5 ? losslessSize(bytes, data) : null;
                default -> null;
            };
            if (result != null) {
                return result;
            }
            pos = data + size + (size & 1);
        }
        return null;
    }

    private static int[] lossySize(byte[] bytes, int data) {
        // 3 字节 frame tag + 3 字节 sync code（9D 01 2A），随后宽/高各 2 字节 LE（低 14 位有效）。
        int width = ((bytes[data + 6] & 0xFF) | ((bytes[data + 7] & 0xFF) << 8)) & 0x3FFF;
        int height = ((bytes[data + 8] & 0xFF) | ((bytes[data + 9] & 0xFF) << 8)) & 0x3FFF;
        return width > 0 && height > 0 ? new int[]{width, height} : null;
    }

    private static int[] losslessSize(byte[] bytes, int data) {
        // data[0]=签名 0x2F，其后 4 字节按 little-endian 位流：width-1（14bit）、height-1（14bit）。
        int bits = (bytes[data + 1] & 0xFF) | ((bytes[data + 2] & 0xFF) << 8)
                | ((bytes[data + 3] & 0xFF) << 16) | ((bytes[data + 4] & 0xFF) << 24);
        return new int[]{(bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1};
    }
}
