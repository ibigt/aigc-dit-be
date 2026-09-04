package cn.aigc.dit.generate.poster;

import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 成片后用 ffmpeg 抽 {@code poster.webp}；图片在 ffmpeg 不可用时回退为原图 key。
 */
@Slf4j
@RequiredArgsConstructor
public class FfmpegPosterExtractor implements PosterExtractor {

    private final ObjectStorage objectStorage;

    @Override
    public String extract(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        String posterKey = toPosterKey(storageKey);
        if (!FfmpegSupport.available()) {
            log.warn("ffmpeg unavailable, poster fallback to original key={}", storageKey);
            return isImage(storageKey) ? storageKey : null;
        }
        Path input = null;
        Path output = null;
        try {
            input = FfmpegSupport.tempFile("dit-src-", suffix(storageKey));
            output = FfmpegSupport.tempFile("dit-poster-", ".webp");
            Files.write(input, objectStorage.getBytes(storageKey));
            if (isImage(storageKey)) {
                FfmpegSupport.run(List.of("-i", input.toString(), output.toString()));
            } else {
                FfmpegSupport.run(List.of(
                        "-ss", "0.3",
                        "-i", input.toString(),
                        "-frames:v", "1",
                        output.toString()));
            }
            objectStorage.put(posterKey, Files.readAllBytes(output), "image/webp");
            return posterKey;
        } catch (Exception e) {
            log.warn("poster extract failed, key={}", storageKey, e);
            return isImage(storageKey) ? storageKey : null;
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    static String toPosterKey(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > 0 ? storageKey.substring(0, dot) : storageKey;
        return base + "-poster.webp";
    }

    static boolean isImage(String storageKey) {
        String lower = storageKey.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private static String suffix(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".bin";
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
}
