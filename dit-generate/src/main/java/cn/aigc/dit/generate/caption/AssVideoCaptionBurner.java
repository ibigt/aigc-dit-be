package cn.aigc.dit.generate.caption;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Burns timed cues through libass, keeping the source video key untouched. */
@Slf4j
@RequiredArgsConstructor
public class AssVideoCaptionBurner {

    private final ObjectStorage objectStorage;
    private final GenerateProperties properties;

    public String burn(String storageKey, List<CaptionCue> cues) {
        return burn(storageKey, cues, null);
    }

    public String burn(String storageKey, List<CaptionCue> cues, String version) {
        if (!StringUtils.hasText(storageKey) || cues == null || cues.isEmpty()
                || alreadyCaptioned(storageKey) || !isVideo(storageKey)) {
            return storageKey;
        }
        List<CaptionCue> usable = cues.stream().filter(CaptionCue::hasText).toList();
        if (usable.isEmpty() || !FfmpegSupport.available()) {
            return storageKey;
        }
        Path font = CaptionFonts.resolve(ffmpeg().getCaptionFont());
        if (font == null) {
            log.warn("no CJK font found, skip ASS caption burn key={}", storageKey);
            return storageKey;
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("dit-ass-");
            Path input = work.resolve("input" + suffix(storageKey));
            Path output = work.resolve("output.mp4");
            Path ass = work.resolve("captions.ass");
            Files.write(input, objectStorage.getBytes(storageKey));
            Files.writeString(ass, AssSubtitleWriter.render(usable), StandardCharsets.UTF_8);
            encode(input, output, ass, font, ffmpeg().getCaptionTimeoutSeconds());
            if (!Files.isRegularFile(output) || Files.size(output) < 32) {
                return storageKey;
            }
            String captionKey = StringUtils.hasText(version)
                    ? toCaptionKey(storageKey, version) : toCaptionKey(storageKey);
            objectStorage.put(captionKey, Files.readAllBytes(output), "video/mp4");
            log.info("burned ASS captions into {} -> {}", storageKey, captionKey);
            return captionKey;
        } catch (Exception e) {
            log.warn("ASS caption burn failed, keep original key={}", storageKey, e);
            return storageKey;
        } finally {
            deleteTree(work);
        }
    }

    static String toCaptionKey(String storageKey) {
        return toCaptionKey(storageKey, null);
    }

    static String toCaptionKey(String storageKey, String version) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > 0 ? storageKey.substring(0, dot) : storageKey;
        String ext = dot > 0 ? storageKey.substring(dot) : ".mp4";
        String suffix = StringUtils.hasText(version) ? "-" + version : "-caption";
        return base + suffix + ext;
    }

    static boolean alreadyCaptioned(String storageKey) {
        String lower = storageKey.toLowerCase(Locale.ROOT);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.contains("-caption");
    }

    static boolean isVideo(String storageKey) {
        String lower = storageKey.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".mkv");
    }

    private static void encode(Path input, Path output, Path ass, Path font, int timeoutSeconds) {
        String vf = "ass=" + escapeFilterPath(ass) + ":fontsdir=" + escapeFilterPath(font.getParent());
        try {
            FfmpegSupport.run(videoArgs(input, output, vf, true), timeoutSeconds);
        } catch (RuntimeException first) {
            deleteQuietly(output);
            FfmpegSupport.run(videoArgs(input, output, vf, false), timeoutSeconds);
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

    private static String escapeFilterPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "'" + value.replace("'", "\\'").replace(":", "\\:") + "'";
    }

    private GenerateProperties.Ffmpeg ffmpeg() {
        return properties.getFfmpeg() == null ? new GenerateProperties.Ffmpeg() : properties.getFfmpeg();
    }

    private static String suffix(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".mp4";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(AssVideoCaptionBurner::deleteQuietly);
        } catch (Exception ignored) { }
    }
}
