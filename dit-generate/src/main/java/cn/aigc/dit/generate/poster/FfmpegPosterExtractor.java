package cn.aigc.dit.generate.poster;

import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 成片后用 ffmpeg 抽 {@code poster.webp} 与 {@code tail.webp}；图片在 ffmpeg 不可用时回退为原图 key。
 */
@Slf4j
@RequiredArgsConstructor
public class FfmpegPosterExtractor implements PosterExtractor {

    /** 抽静帧时限宽，避免容器内 libwebp 对 720p+ 整帧编码 OOM。 */
    private static final String STILL_SCALE = "scale='min(960,iw)':-2";

    private final ObjectStorage objectStorage;

    @Override
    public String extract(String storageKey) {
        return extractFrame(storageKey, false);
    }

    @Override
    public String extractTail(String storageKey) {
        return extractFrame(storageKey, true);
    }

    @Override
    public VideoStills extractStills(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return new VideoStills(null, null);
        }
        if (isImage(storageKey)) {
            String poster = extract(storageKey);
            return new VideoStills(poster, poster);
        }
        if (!FfmpegSupport.available()) {
            log.warn("ffmpeg unavailable, stills skipped key={}", storageKey);
            return new VideoStills(null, null);
        }
        Path input = null;
        Path posterOut = null;
        Path tailOut = null;
        try {
            input = FfmpegSupport.tempFile("dit-src-", suffix(storageKey));
            Files.write(input, objectStorage.getBytes(storageKey));
            posterOut = FfmpegSupport.tempFile("dit-poster-", ".webp");
            tailOut = FfmpegSupport.tempFile("dit-tail-", ".webp");
            extractNearStart(input, posterOut);
            String posterKey = toPosterKey(storageKey);
            objectStorage.put(posterKey, Files.readAllBytes(posterOut), "image/webp");
            String tailKey = null;
            try {
                extractLastFrame(input, tailOut);
                tailKey = toTailKey(storageKey);
                objectStorage.put(tailKey, Files.readAllBytes(tailOut), "image/webp");
            } catch (Exception e) {
                log.warn("tail extract failed, key={}", storageKey, e);
            }
            return new VideoStills(posterKey, tailKey);
        } catch (Exception e) {
            log.warn("stills extract failed, key={}", storageKey, e);
            return new VideoStills(null, null);
        } finally {
            deleteQuietly(input);
            deleteQuietly(posterOut);
            deleteQuietly(tailOut);
        }
    }

    private String extractFrame(String storageKey, boolean tail) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        String outKey = tail ? toTailKey(storageKey) : toPosterKey(storageKey);
        if (!FfmpegSupport.available()) {
            log.warn("ffmpeg unavailable, poster fallback to original key={}", storageKey);
            return isImage(storageKey) ? storageKey : null;
        }
        Path input = null;
        Path output = null;
        try {
            input = FfmpegSupport.tempFile("dit-src-", suffix(storageKey));
            output = FfmpegSupport.tempFile(tail ? "dit-tail-" : "dit-poster-", ".webp");
            Files.write(input, objectStorage.getBytes(storageKey));
            if (isImage(storageKey)) {
                FfmpegSupport.run(stillFromImageArgs(input, output));
            } else if (tail) {
                extractLastFrame(input, output);
            } else {
                extractNearStart(input, output);
            }
            objectStorage.put(outKey, Files.readAllBytes(output), "image/webp");
            return outKey;
        } catch (Exception e) {
            log.warn("{} extract failed, key={}", tail ? "tail" : "poster", storageKey, e);
            return (!tail && isImage(storageKey)) ? storageKey : null;
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static void extractNearStart(Path input, Path output) throws Exception {
        FfmpegSupport.run(stillFrameArgs(
                List.of("-ss", "0.3", "-i", input.toString()),
                output));
    }

    /**
     * 优先按片长定位尾帧（低内存）；再退到 -sseof。不用 reverse / 全片编码。
     */
    private static void extractLastFrame(Path input, Path output) throws Exception {
        List<List<String>> attempts = new ArrayList<>();
        Double duration = FfmpegSupport.durationSeconds(input);
        if (duration != null && duration > 0.05) {
            String ss = String.format(Locale.US, "%.3f", Math.max(0, duration - 0.12));
            // 先 seek 再 decode，只吃尾部几帧，内存最低。
            attempts.add(stillFrameArgs(List.of("-ss", ss, "-i", input.toString()), output));
            attempts.add(stillFrameArgs(List.of("-i", input.toString(), "-ss", ss), output));
        }
        for (String seek : List.of("-0.05", "-0.5", "-1")) {
            attempts.add(stillFrameArgs(List.of("-sseof", seek, "-i", input.toString()), output));
        }
        Exception last = null;
        for (List<String> args : attempts) {
            try {
                Files.deleteIfExists(output);
                FfmpegSupport.run(args);
                if (Files.exists(output) && Files.size(output) > 0) {
                    return;
                }
            } catch (Exception e) {
                last = e;
                log.warn("tail attempt failed, args={}", args, e);
            }
        }
        if (last instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("tail extract failed", last);
    }

    /**
     * 强制静态 libwebp（不要默认 libwebp_anim），并限宽，避免容器 OOM。
     */
    private static List<String> stillFrameArgs(List<String> inputArgs, Path output) {
        List<String> args = new ArrayList<>(inputArgs);
        args.add("-an");
        args.add("-vf");
        args.add(STILL_SCALE);
        args.add("-frames:v");
        args.add("1");
        args.add("-c:v");
        args.add("libwebp");
        args.add("-quality");
        args.add("80");
        args.add(output.toString());
        return args;
    }

    private static List<String> stillFromImageArgs(Path input, Path output) {
        return List.of(
                "-i", input.toString(),
                "-vf", STILL_SCALE,
                "-frames:v", "1",
                "-c:v", "libwebp",
                "-quality", "80",
                output.toString());
    }

    static String toPosterKey(String storageKey) {
        return withSuffix(storageKey, "-poster.webp");
    }

    static String toTailKey(String storageKey) {
        return withSuffix(storageKey, "-tail.webp");
    }

    private static String withSuffix(String storageKey, String suffix) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > 0 ? storageKey.substring(0, dot) : storageKey;
        return base + suffix;
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
