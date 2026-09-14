package cn.aigc.dit.generate.caption;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 出片成功后把中文字幕烧进画面。缺 ffmpeg / 字体 / 失败时返回原 key，不让出片失败。
 */
@Slf4j
@RequiredArgsConstructor
public class FfmpegVideoCaptionBurner implements VideoCaptionBurner {

    private final ObjectStorage objectStorage;
    private final GenerateProperties properties;

    @Override
    public String burn(String storageKey, List<CaptionCue> cues) {
        if (storageKey == null || storageKey.isBlank() || cues == null || cues.isEmpty()) {
            return storageKey;
        }
        if (alreadyCaptioned(storageKey) || !isVideo(storageKey)) {
            return storageKey;
        }
        List<CaptionCue> usable = cues.stream().filter(CaptionCue::hasText).toList();
        if (usable.isEmpty()) {
            return storageKey;
        }
        if (!FfmpegSupport.available()) {
            log.warn("ffmpeg unavailable, skip caption burn key={}", storageKey);
            return storageKey;
        }
        Path font = CaptionFonts.resolve(ffmpeg().getCaptionFont());
        if (font == null) {
            log.warn("no CJK font found, skip caption burn key={}", storageKey);
            return storageKey;
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("dit-cap-");
            Path input = work.resolve("in" + suffix(storageKey));
            Path output = work.resolve("out.mp4");
            Files.write(input, objectStorage.getBytes(storageKey));
            List<DrawtextFilter.Overlay> overlays = new ArrayList<>();
            int index = 0;
            for (CaptionCue cue : usable) {
                Path textFile = work.resolve("c" + index + ".txt");
                Files.writeString(textFile, CaptionTexts.wrap(cue.text()), StandardCharsets.UTF_8);
                overlays.add(new DrawtextFilter.Overlay(cue.startSec(), cue.endSec(), textFile));
                index++;
            }
            String vf = DrawtextFilter.build(font, overlays);
            encode(input, output, vf, ffmpeg().getCaptionTimeoutSeconds());
            if (!Files.isRegularFile(output) || Files.size(output) < 32) {
                log.warn("caption output empty, keep original key={}", storageKey);
                return storageKey;
            }
            String captionKey = toCaptionKey(storageKey);
            objectStorage.put(captionKey, Files.readAllBytes(output), "video/mp4");
            log.info("burned captions into {} -> {}", storageKey, captionKey);
            return captionKey;
        } catch (Exception e) {
            log.warn("caption burn failed, keep original key={}", storageKey, e);
            return storageKey;
        } finally {
            deleteTree(work);
        }
    }

    static String toCaptionKey(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > 0 ? storageKey.substring(0, dot) : storageKey;
        String ext = dot > 0 ? storageKey.substring(dot) : ".mp4";
        return base + "-caption" + ext;
    }

    static boolean alreadyCaptioned(String storageKey) {
        String lower = storageKey.toLowerCase(Locale.ROOT);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.contains("-caption.");
    }

    static boolean isVideo(String storageKey) {
        String lower = storageKey.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".mkv");
    }

    private void encode(Path input, Path output, String vf, int timeoutSeconds) {
        try {
            FfmpegSupport.run(videoArgs(input, output, vf, true), timeoutSeconds);
        } catch (BizException first) {
            deleteQuietly(output);
            try {
                FfmpegSupport.run(videoArgs(input, output, vf, false), timeoutSeconds);
            } catch (BizException second) {
                deleteQuietly(output);
                FfmpegSupport.run(videoArgsNoAudio(input, output, vf), timeoutSeconds);
            }
        }
    }

    private static List<String> videoArgs(Path input, Path output, String vf, boolean copyAudio) {
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(input.toString());
        args.add("-vf");
        args.add(vf);
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("18");
        args.add("-c:a");
        args.add(copyAudio ? "copy" : "aac");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());
        return args;
    }

    private static List<String> videoArgsNoAudio(Path input, Path output, String vf) {
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(input.toString());
        args.add("-vf");
        args.add(vf);
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("18");
        args.add("-an");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());
        return args;
    }

    private GenerateProperties.Ffmpeg ffmpeg() {
        return properties.getFfmpeg() == null ? new GenerateProperties.Ffmpeg() : properties.getFfmpeg();
    }

    private static String suffix(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".mp4";
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

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // temp cleanup
                }
            });
        } catch (Exception ignored) {
            // temp cleanup
        }
    }
}
