package cn.aigc.dit.server.media;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 列表预览用缩略图：按最长边缩放并转 JPEG，便于缓存与节省带宽。
 */
@Slf4j
public final class ImageThumbnails {

    public static final int DEFAULT_LIST_WIDTH = 480;

    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 1280;
    private static final int[] WIDTH_BUCKETS = {160, 240, 320, 480, 640, 960, 1280};
    private static final float JPEG_QUALITY = 0.82f;

    private ImageThumbnails() {
    }

    public record Thumb(byte[] bytes, String contentType) {
    }

    public static Integer normalizeWidth(Integer width) {
        if (width == null || width <= 0) {
            return null;
        }
        int clamped = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        for (int bucket : WIDTH_BUCKETS) {
            if (clamped <= bucket) {
                return bucket;
            }
        }
        return MAX_WIDTH;
    }

    public static boolean supportsThumbnail(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        String type = MediaUrls.contentType(storageKey, "");
        return "image/jpeg".equals(type)
                || "image/png".equals(type)
                || "image/gif".equals(type)
                || "image/webp".equals(type);
    }

    public static String cacheKey(String storageKey, int width) {
        return cacheKey(storageKey, width, -1L);
    }

    /**
     * 缩略图缓存键必须带上原图字节数：同一 key 被覆盖写入后，旧缩略图不能继续命中，
     * 否则列表/历史用 w=480 仍是旧图，大预览 w=960 或原图已是新图。
     */
    public static String cacheKey(String storageKey, int width, long sourceBytes) {
        if (sourceBytes > 0) {
            return storageKey + ".__thumb_w" + width + "_s" + sourceBytes + ".jpg";
        }
        return storageKey + ".__thumb_w" + width + ".jpg";
    }

    /** 列出各档位可能残留的旧缓存键（含不带 size 的历史格式），便于覆盖写入后清理。 */
    public static List<String> legacyCacheKeys(String storageKey) {
        List<String> keys = new ArrayList<>();
        for (int bucket : WIDTH_BUCKETS) {
            keys.add(cacheKey(storageKey, bucket));
        }
        return keys;
    }

    /**
     * 将原图缩放到不超过 {@code maxWidth} 的 JPEG；无法解码时返回 null。
     */
    public static Thumb resizeToJpeg(byte[] original, int maxWidth) {
        if (original == null || original.length == 0 || maxWidth <= 0) {
            return null;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                return null;
            }
            int srcW = source.getWidth();
            int srcH = source.getHeight();
            if (srcW <= 0 || srcH <= 0) {
                return null;
            }
            int targetW = srcW;
            int targetH = srcH;
            if (srcW > maxWidth) {
                targetW = maxWidth;
                targetH = Math.max(1, (int) Math.round(srcH * (maxWidth / (double) srcW)));
            }
            BufferedImage rgb = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetW, targetH);
                g.drawImage(source, 0, 0, targetW, targetH, null);
            } finally {
                g.dispose();
            }
            byte[] jpeg = writeJpeg(rgb);
            return jpeg == null ? null : new Thumb(jpeg, "image/jpeg");
        } catch (Exception e) {
            log.debug("thumbnail resize failed", e);
            return null;
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", fallback)) {
                return null;
            }
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
