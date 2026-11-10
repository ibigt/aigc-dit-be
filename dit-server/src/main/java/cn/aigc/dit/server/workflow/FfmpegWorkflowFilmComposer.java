package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class FfmpegWorkflowFilmComposer implements WorkflowFilmComposer {

    // ffprobe uses the same demuxer aliases for MP4, QuickTime and other ISO BMFF containers.
    // Accept only known MP4 brands, case-insensitively after stripping fourcc space padding.
    private static final Set<String> MP4_MAJOR_BRANDS = Set.of(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
            "mp41", "mp42", "avc1", "dash", "m4v", "m4a");

    interface Runner {
        boolean available();

        boolean hasAudio(Path input) throws Exception;

        void run(List<String> args, long timeoutSeconds) throws Exception;
    }

    private static final Runner SYSTEM_RUNNER = new Runner() {
        @Override
        public boolean available() {
            return FfmpegSupport.available();
        }

        @Override
        public boolean hasAudio(Path input) throws Exception {
            Process process = new ProcessBuilder(FfmpegSupport.ffprobeBinary(), "-v", "error", "-select_streams", "a",
                    "-show_entries", "stream=index", "-of", "csv=p=0", input.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = awaitProcess(process, 8);
            if (!finished) {
                stopAndReap(process);
                throw new IOException("ffprobe timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("ffprobe failed: " + output);
            }
            return !output.isBlank();
        }

        @Override
        public void run(List<String> args, long timeoutSeconds) {
            execute(args, timeoutSeconds);
        }
    };

    static void execute(List<String> args, long timeoutSeconds) {
        Path logFile = null;
        try {
            logFile = FfmpegSupport.tempFile("dit-workflow-ffmpeg-", ".log");
            // 必须用 FfmpegSupport.ffmpegBinary()（走 -Ddit.ffmpeg.path / DIT_FFMPEG_PATH 解析），
            // 裸 "ffmpeg" 依赖 PATH——Java 进程的 PATH 里没有 ffmpeg 时直接「无法启动 ffmpeg」
            List<String> command = new ArrayList<>(List.of(FfmpegSupport.ffmpegBinary(), "-y"));
            command.addAll(args);
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            long timeout = timeoutSeconds < 1 ? 120 : timeoutSeconds;
            if (!awaitProcess(process, timeout)) {
                stopAndReap(process);
                throw new BizException(ErrorCode.INTERNAL, "ffmpeg 超时");
            }
            if (process.exitValue() != 0) {
                // ffmpeg 的 stderr 是定位问题的唯一线索（滤镜语法/编解码器/输入问题都在这），
                // 截尾带上：完整日志进 log.warn，最后 500 字符随异常透给前端，别再让「编码失败」吞根因
                String full = Files.readString(logFile, StandardCharsets.UTF_8);
                log.warn("workflow ffmpeg failed: {}", full);
                String tail = full.length() > 500 ? full.substring(full.length() - 500) : full;
                throw new BizException(ErrorCode.INTERNAL, "ffmpeg 执行失败：" + tail);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "无法启动 ffmpeg");
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException e) {
                    log.warn("workflow ffmpeg log cleanup failed, path={}", logFile, e);
                }
            }
        }
    }

    static boolean awaitProcess(Process process, long timeoutSeconds) {
        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            stopAndReap(process);
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "ffmpeg 执行中断");
        }
    }

    private static void stopAndReap(Process process) {
        process.destroy();
        boolean interrupted = false;
        boolean stopped = false;
        try {
            stopped = process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (!stopped) {
            process.destroyForcibly();
            while (true) {
                try {
                    process.waitFor();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final ObjectStorage storage;
    private final PosterExtractor posterExtractor;
    private final GenerateProperties generateProperties;
    private final Runner runner;

    @Autowired
    public FfmpegWorkflowFilmComposer(ObjectStorage storage, PosterExtractor posterExtractor,
                                      GenerateProperties generateProperties) {
        this(storage, posterExtractor, generateProperties, SYSTEM_RUNNER);
    }

    FfmpegWorkflowFilmComposer(ObjectStorage storage, PosterExtractor posterExtractor,
                               GenerateProperties generateProperties, Runner runner) {
        this.storage = storage;
        this.posterExtractor = posterExtractor;
        this.generateProperties = generateProperties;
        this.runner = runner;
    }

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    @Override
    public FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target) {
        return findExisting(userId, runId, nodeId, clips, target, List.of());
    }

    @Override
    public FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                                   List<AudioClip> audioClips) {
        validate(userId, runId, nodeId, clips);
        String key = filmKey(userId, runId, nodeId);
        if (!exists(key)) {
            return null;
        }
        Path temporary = null;
        try {
            temporary = FfmpegSupport.tempFile("dit-workflow-recover-", ".mp4");
            try (InputStream film = storage.get(key)) {
                Files.copy(film, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!playableMp4(temporary)) {
                log.warn("stored workflow film is invalid and will be recomposed, key={}", key);
                return null;
            }
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取已有工作流成片失败");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.warn("workflow film recovery cleanup failed, path={}", temporary, e);
                }
            }
        }
        String posterKey = key.substring(0, key.length() - ".mp4".length()) + "-poster.webp";
        if (!exists(posterKey)) {
            try {
                posterKey = posterExtractor.extract(key);
            } catch (Exception e) {
                log.warn("workflow film poster recovery failed, key={}", key, e);
                posterKey = null;
            }
        }
        double duration = clips.stream().mapToDouble(clip -> clip.trimEnd() - clip.trimStart()).sum();
        int width = target == null ? DEFAULT_WIDTH : target.width();
        int height = target == null ? DEFAULT_HEIGHT : target.height();
        return new FilmOutput(key, posterKey, duration, width, height);
    }

    private static boolean playableMp4(Path film) {
        try (InputStream input = Files.newInputStream(film)) {
            byte[] header = input.readNBytes(8);
            if (header.length < 8 || header[4] != 'f' || header[5] != 't'
                    || header[6] != 'y' || header[7] != 'p') {
                return false;
            }
            Double duration = FfmpegSupport.durationSeconds(film);
            if (duration == null || !Double.isFinite(duration) || duration <= 0) {
                return false;
            }
            Path probeOutput = FfmpegSupport.tempFile("dit-workflow-probe-", ".txt");
            try {
                Process process = new ProcessBuilder(FfmpegSupport.ffprobeBinary(), "-v", "error", "-count_frames",
                        "-select_streams", "v:0", "-show_entries",
                        "stream=nb_read_frames:format=format_name:format_tags=major_brand",
                        "-of", "default=noprint_wrappers=1", film.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(probeOutput.toFile())
                        .start();
                if (!awaitProcess(process, 90)) {
                    stopAndReap(process);
                    return false;
                }
                if (process.exitValue() != 0) {
                    return false;
                }
                String probe = Files.readString(probeOutput);
                String formatNames = probe.lines().filter(line -> line.startsWith("format_name="))
                        .map(line -> line.substring("format_name=".length()))
                        .findFirst().orElse("");
                String brand = probe.lines().filter(line -> line.startsWith("TAG:major_brand="))
                        .map(line -> line.substring("TAG:major_brand=".length()).trim().toLowerCase(Locale.ROOT))
                        .findFirst().orElse("");
                String frames = probe.lines().filter(line -> line.startsWith("nb_read_frames="))
                        .map(line -> line.substring("nb_read_frames=".length()))
                        .findFirst().orElse("0");
                return java.util.Arrays.asList(formatNames.split(",")).contains("mp4")
                        && MP4_MAJOR_BRANDS.contains(brand)
                        && Long.parseLong(frames) > 0;
            } finally {
                Files.deleteIfExists(probeOutput);
            }
        } catch (Exception e) {
            log.warn("stored workflow film probe failed, path={}", film, e);
            return false;
        }
    }

    private boolean exists(String key) {
        try {
            return storage.size(key) > 0;
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target) {
        return compose(userId, runId, nodeId, clips, target, List.of());
    }

    @Override
    public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                              List<AudioClip> audioClips) {
        return compose(userId, runId, nodeId, clips, target, audioClips, null, null);
    }

    @Override
    public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                              List<AudioClip> audioClips, List<WorkflowFilmComposer.ClipStyle> styles,
                              List<WorkflowFilmComposer.OverlayClip> overlayClips) {
        List<WorkflowFilmComposer.ClipStyle> clipStyles = styles == null ? null
                : (styles.size() == clips.size() ? styles : null);
        List<WorkflowFilmComposer.OverlayClip> overlays = overlayClips == null ? List.of() : overlayClips;
        validate(userId, runId, nodeId, clips);
        List<AudioClip> tracks = audioClips == null ? List.of() : audioClips;
        for (AudioClip track : tracks) {
            if (track == null || track.storageKey() == null || track.storageKey().isBlank()
                    || !Double.isFinite(track.start()) || track.start() < 0
                    || !Double.isFinite(track.trimStart()) || !Double.isFinite(track.trimEnd())
                    || track.trimStart() < 0 || track.trimEnd() <= track.trimStart()
                    || !Double.isFinite(track.volume()) || track.volume() < 0 || track.volume() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "音频片段参数无效");
            }
        }
        if (!runner.available()) {
            throw new BizException(ErrorCode.INTERNAL, "服务器未安装 ffmpeg，无法生成工作流成片");
        }
        List<Path> temps = new ArrayList<>();
        try {
            List<Path> inputs = new ArrayList<>();
            List<Boolean> hasAudio = new ArrayList<>();
            for (Clip clip : clips) {
                Path input = FfmpegSupport.tempFile("dit-workflow-clip-", ".mp4");
                temps.add(input);
                try {
                    try (InputStream source = storage.get(clip.storageKey())) {
                        Files.copy(source, input, StandardCopyOption.REPLACE_EXISTING);
                    }
                    hasAudio.add(runner.hasAudio(input));
                } catch (Exception e) {
                    throw new BizException(ErrorCode.INTERNAL, "读取源视频失败");
                }
                Double sourceDuration = FfmpegSupport.durationSeconds(input);
                if (sourceDuration == null || !Double.isFinite(sourceDuration) || sourceDuration <= 0) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "源视频无法读取有效时长");
                }
                if (clip.trimStart() >= sourceDuration || clip.trimEnd() > sourceDuration) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "视频裁剪范围超过源视频时长");
                }
                inputs.add(input);
            }
            // 音轨文件落盘为临时文件（扩展名不参与 ffmpeg 解码，内容为准）。
            // 音频轨是增强项：裁剪范围超出素材真实时长时**钳制到可用范围**而不是抛错
            // （上游产物的 durationSec/durationMs 元数据经常缺失，前端按 5s 兜底落轨，
            // 实际素材更短——例如 3.4s 的配音配了 trimEnd=5.95——不该因此拦掉整个成片）。
            // 整段不可用（钳制后 trimEnd<=trimStart）则静默丢弃该条音轨，与 Planner 静默丢弃口径一致。
            List<Path> audioInputs = new ArrayList<>();
            List<AudioClip> usableTracks = new ArrayList<>();
            for (AudioClip track : tracks) {
                Path input = FfmpegSupport.tempFile("dit-workflow-audio-", ".audio");
                temps.add(input);
                try {
                    try (InputStream source = storage.get(track.storageKey())) {
                        Files.copy(source, input, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new BizException(ErrorCode.INTERNAL, "读取音频素材失败");
                }
                Double audioDuration = FfmpegSupport.durationSeconds(input);
                if (audioDuration == null || !Double.isFinite(audioDuration) || audioDuration <= 0) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "音频素材无法读取有效时长");
                }
                double clampedStart = Math.min(track.trimStart(), Math.max(0, audioDuration - 0.01));
                double clampedEnd = Math.min(track.trimEnd(), audioDuration);
                if (clampedEnd <= clampedStart) {
                    continue;
                }
                AudioClip usable = clampedStart == track.trimStart() && clampedEnd == track.trimEnd()
                        ? track
                        : new AudioClip(track.storageKey(), track.start(), clampedStart, clampedEnd,
                                track.muted(), track.volume());
                usableTracks.add(usable);
                audioInputs.add(input);
            }
            AudioClip[] trackArray = usableTracks.toArray(AudioClip[]::new);
            List<AudioClip> effectiveTracks = List.of(trackArray);
            Path output = FfmpegSupport.tempFile("dit-workflow-film-", ".mp4");
            temps.add(output);
            // 叠加层渲染（排期 #7）：文字/图形每段烧成**独立**的透明 PNG。
            // 必须一段一张：多段文字若挤在同一张图里，各段的时间窗会互相串
            // （同一张图里所有文字都在，只是按不同 enable 区间显示）。
            List<Path> overlayPngs = new ArrayList<>();
            if (overlayClips != null && !overlayClips.isEmpty()) {
                int ow = target == null ? DEFAULT_WIDTH : target.width();
                int oh = target == null ? DEFAULT_HEIGHT : target.height();
                for (WorkflowFilmComposer.OverlayClip clip : overlayClips) {
                    // 每段都渲染（含无效段），保证 PNG 与 overlays 严格同序——args 侧按下标取图
                    Path png = FfmpegSupport.tempFile("dit-workflow-overlay-", ".png");
                    temps.add(png);
                    OverlayRenderer.writeTempPng(
                            List.of(new OverlayRenderer.Overlay(clip.kind(), clip.start(), clip.end(),
                                    clip.text(), clip.color(), clip.fontSize(), clip.weight(), clip.align(),
                                    clip.shape(), clip.x(), clip.y(), clip.scale(), clip.opacity())),
                            ow, oh, png);
                    overlayPngs.add(png);
                }
            }
            long timeout = generateProperties.getFfmpeg() == null ? 900
                    : generateProperties.getFfmpeg().getConcatEncodeTimeoutSeconds();
            try {
                runner.run(args(inputs, clips, hasAudio, target, output, effectiveTracks, audioInputs,
                        clipStyles, overlayPngs.isEmpty() ? null : overlayPngs.get(0), overlayClips, overlayPngs), timeout);
            } catch (BizException e) {
                if (e.getMessage() != null && e.getMessage().contains("超时")) {
                    throw new BizException(ErrorCode.INTERNAL, "工作流成片编码超时");
                }
                // BizException 已带根因（如「ffmpeg 执行失败：<stderr 尾部>」），原样透传，
                // 别再包成「成片编码失败」吞掉 stderr——那是定位滤镜/编解码问题的唯一线索
                throw e;
            } catch (Exception e) {
                log.warn("workflow film compose failed unexpectedly", e);
                throw new BizException(ErrorCode.INTERNAL, "工作流成片编码失败：" + e.getMessage());
            }
            if (!playableMp4(output)) {
                throw new BizException(ErrorCode.INTERNAL, "工作流成片不是可播放的 MP4 视频");
            }
            String key = filmKey(userId, runId, nodeId);
            try {
                try (InputStream film = Files.newInputStream(output)) {
                    storage.put(key, film, Files.size(output), "video/mp4");
                }
            } catch (Exception e) {
                throw new BizException(ErrorCode.INTERNAL, "工作流成片存储失败");
            }
            String posterKey = null;
            try {
                posterKey = posterExtractor.extract(key);
            } catch (Exception e) {
                log.warn("workflow film poster extraction failed, key={}", key, e);
            }
            double duration = clips.stream().mapToDouble(clip -> clip.trimEnd() - clip.trimStart()).sum();
            int width = target == null ? DEFAULT_WIDTH : target.width();
            int height = target == null ? DEFAULT_HEIGHT : target.height();
            return new FilmOutput(key, posterKey, duration, width, height);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "工作流成片编码失败");
        } finally {
            for (Path temp : temps) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("workflow film temp cleanup failed, path={}", temp, e);
                }
            }
        }
    }

    private static void validate(Long userId, Long runId, String nodeId, List<Clip> clips) {
        if (userId == null || userId < 1 || runId == null || runId < 1
                || nodeId == null || !nodeId.matches("[A-Za-z0-9_-]+")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工作流成片参数无效");
        }
        if (clips == null || clips.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要合成的视频片段");
        }
        for (Clip clip : clips) {
            if (clip == null || clip.storageKey() == null || clip.storageKey().isBlank()
                    || !Double.isFinite(clip.trimStart()) || !Double.isFinite(clip.trimEnd())
                    || clip.trimStart() < 0 || clip.trimEnd() <= clip.trimStart()
                    || !Double.isFinite(clip.volume()) || clip.volume() < 0 || clip.volume() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "视频片段裁剪范围或音量无效");
            }
        }
    }

    private static String filmKey(Long userId, Long runId, String nodeId) {
        return "users/" + userId + "/workflows/" + runId + "/" + nodeId + "/film.mp4";
    }

    static List<String> args(List<Path> inputs, List<Clip> clips, Path output) {
        return args(inputs, clips, java.util.Collections.nCopies(clips.size(), true), null, output);
    }

    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, Path output) {
        return args(inputs, clips, hasAudio, null, output);
    }

    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target, Path output) {
        return args(inputs, clips, hasAudio, target, output, List.of(), List.of());
    }

    /**
     * 完整参数构造：视频片段拼接成主轨（含各自原声/静音），音频轨片段经 atrim+volume+adelay
     * 后 amix 到拼接结果上。audioInputs 与 audioClips 一一对应（本地临时文件，由 compose 落盘）。
     * 音轨为空时输出与旧版完全一致（无 amix 段）。
     */
    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target,
                             Path output, List<AudioClip> audioClips, List<Path> audioInputs) {
        return args(inputs, clips, hasAudio, target, output, audioClips, audioInputs, null, null, List.of(), List.of());
    }

    /**
     * 排期 #7 全量构造：styles 与 clips 一一对应（可空 = 无样式）；overlays 为文字/图形烧录层，
     * overlayPngPaths 与 overlays 同序（每段一张透明 PNG）。
     * 三者缺省/为空时 filter 与低阶重载逐字节一致（有测试锁定）。
     */
    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target,
                             Path output, List<AudioClip> audioClips, List<Path> audioInputs,
                             List<WorkflowFilmComposer.ClipStyle> styles, Path overlayPng,
                             List<WorkflowFilmComposer.OverlayClip> overlays) {
        return args(inputs, clips, hasAudio, target, output, audioClips, audioInputs, styles, overlayPng,
                overlays, List.of());
    }

    /**
     * 排期 #7 全量构造（多张叠加 PNG）：overlayPngPaths 与 overlays 同序，每段一张图，
     * 避免多段文字挤在同一张图里互相串时间窗。
     * 三者缺省/为空时 filter 与低阶重载逐字节一致（有测试锁定）。
     */
    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target,
                             Path output, List<AudioClip> audioClips, List<Path> audioInputs,
                             List<WorkflowFilmComposer.ClipStyle> styles, Path overlayPng,
                             List<WorkflowFilmComposer.OverlayClip> overlays,
                             List<Path> overlayPngPaths) {
        if (inputs.size() != clips.size() || hasAudio.size() != clips.size()) {
            throw new IllegalArgumentException("clip inputs mismatch");
        }
        List<AudioClip> tracks = audioClips == null ? List.of() : audioClips;
        if (audioInputs != null && audioInputs.size() != tracks.size()) {
            throw new IllegalArgumentException("audio inputs mismatch");
        }
        List<WorkflowFilmComposer.ClipStyle> clipStyles = styles == null || styles.size() != clips.size()
                ? null : styles;
        boolean styled = clipStyles != null && clipStyles.stream().anyMatch(s -> s != null && !s.isEmpty());
        boolean hasTransitions = styled && clipStyles.stream().anyMatch(s -> s != null && s.transitionId() != null);
        // 叠加层：每段一张 PNG（overlayPngPaths 与 overlays 同序）；单张时用 overlayPng。
        List<Path> overlayFiles = overlayPngPaths == null || overlayPngPaths.isEmpty()
                ? (overlayPng == null ? List.<Path>of() : List.of(overlayPng))
                : overlayPngPaths;
        boolean hasOverlay = !overlayFiles.isEmpty() && overlays != null && !overlays.isEmpty();
        if (target != null && !SUPPORTED_SIZES.containsValue(target)) {
            throw new IllegalArgumentException("不支持的成片尺寸：" + target.text());
        }
        int width = target == null ? DEFAULT_WIDTH : target.width();
        int height = target == null ? DEFAULT_HEIGHT : target.height();
        List<String> args = new ArrayList<>(List.of("-hide_banner"));
        for (Path input : inputs) {
            args.add("-i");
            args.add(input.toString());
        }
        int silentInput = inputs.size();
        List<Integer> audioIndexes = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            if (hasAudio.get(i)) {
                audioIndexes.add(i);
            } else {
                audioIndexes.add(silentInput++);
                args.addAll(List.of("-f", "lavfi", "-t", Double.toString(clips.get(i).trimEnd() - clips.get(i).trimStart()),
                        "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"));
            }
        }
        // 音轨输入：追加在视频/静音输入之后，索引连续分配
        for (Path audioInput : (audioInputs == null ? List.<Path>of() : audioInputs)) {
            args.add("-i");
            args.add(audioInput.toString());
        }
        // 叠加层 PNG 输入：追加在所有输入之后，每段一张（与 overlays 同序）
        int overlayInput = silentInput + tracks.size();
        for (Path png : overlayFiles) {
            args.add("-i");
            args.add(png.toString());
        }
        StringBuilder filter = new StringBuilder();
        // 每路输入：trim → setpts → fps → scale → pad → [eq/hue/gblur 样式滤镜] → setsar → format
        // 无样式片段的链与旧版完全一致（仅当 styled 才插入滤镜段，保持逐字节兼容）。
        List<Double> durations = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            double duration = clip.trimEnd() - clip.trimStart();
            durations.add(duration);
            String effectFilters = styled && clipStyles.get(i) != null
                    ? effectFilterOf(clipStyles.get(i)) : "";
            filter.append("[").append(i).append(":v]trim=start=").append(clip.trimStart())
                    .append(":end=").append(clip.trimEnd())
                    .append(",setpts=PTS-STARTPTS,fps=30,scale=").append(width).append(":").append(height)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(width).append(":").append(height)
                    .append(":(ow-iw)/2:(oh-ih)/2");
            if (!effectFilters.isEmpty()) {
                filter.append(",").append(effectFilters);
            }
            filter.append(",setsar=1,format=yuv420p[v").append(i).append("]; ");
            filter.append("[").append(audioIndexes.get(i)).append(":a]");
            if (hasAudio.get(i)) {
                filter.append("atrim=start=").append(clip.trimStart()).append(":end=").append(clip.trimEnd());
            } else {
                filter.append("atrim=duration=").append(duration);
            }
            filter.append(",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=")
                    .append(clip.muted() ? "0" : clip.volume()).append("[a").append(i).append("]; ");
        }
        // 视频拼接：无转场走 concat（与旧版一致）；有转场走 xfade 链（音频仍 concat）。
        // xfade 偏移 = 前面所有片段时长之和 - 累计重叠，逐段折叠。
        if (!hasTransitions) {
            for (int i = 0; i < clips.size(); i++) {
                filter.append("[v").append(i).append("][a").append(i).append("]");
            }
        } else {
            // 音频照旧 concat；视频单独抽出来走 xfade，最后再汇合
            for (int i = 0; i < clips.size(); i++) {
                filter.append("[a").append(i).append("]");
            }
        }
        // 音频轨混入：每条 atrim 裁剪 → volume（muted 置 0）→ adelay 对齐成片内位置 → amix。
        // duration=first 以视频拼接结果为准，超出成片尾部的音频被截断，与预览语义一致。
        // 无音轨时 concat 直接产出 [v][a]（与旧版输出完全一致）；有音轨时产出 [v][a] 供 amix 引用。
        String audioMap;
        boolean xfade = hasTransitions;
        if (tracks.isEmpty() && !xfade) {
            filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]; ");
            audioMap = "[a]";
        } else if (!xfade) {
            filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]; ");
            appendAudioMix(filter, silentInput, tracks);
            audioMap = "[aout]";
        } else {
            // 转场路径：视频 xfade 折叠出 [vout]；音频 concat 出 [a]（或 amix 出 [aout]）
            filter.append("concat=n=").append(clips.size()).append(":v=0:a=1[a]");
            appendXfadeChain(filter, clips, clipStyles, durations);
            if (tracks.isEmpty()) {
                audioMap = "[a]";
            } else {
                // amix 的第一路要接 [vout] 对应不了——amix 只吃音频；视频 [vout] 单独 map。
                // 这里音频轨与 [a] amix（把 [a] 作为 amix 第一输入）。
                filter.append("[a]");
                for (int i = 0; i < tracks.size(); i++) {
                    filter.append("[bg").append(i).append("]");
                }
                filter.append("amix=inputs=").append(tracks.size() + 1)
                        .append(":duration=first:dropout_transition=0[aout]");
                audioMap = "[aout]";
            }
        }
        // 叠加层：每个 overlay 片段单独一张透明 PNG，按各自时间区间 overlay 到主画面上。
        // ffmpeg overlay 滤镜语义：第一个输入是主画面，第二个是被叠加的覆盖层。
        // 写成 [png:v][v] 会把视频叠到透明 PNG 上（画面全黑），必须是 [v][png:v]。
        String videoMap = xfade ? "[vout]" : "[v]";
        if (hasOverlay) {
            StringBuilder chain = new StringBuilder();
            String current = videoMap;
            int index = 0;
            for (int i = 0; i < overlays.size(); i++) {
                WorkflowFilmComposer.OverlayClip overlay = overlays.get(i);
                if (overlay == null || overlay.end() <= overlay.start()) continue;
                String label = "[ov" + index + "]";
                // overlayFiles 与 overlays 严格同序（compose 按同序渲染 PNG），按下标取
                chain.append(current).append("[").append(overlayInput + i).append(":v]")
                        .append("overlay=enable='between(t,").append(fmt(overlay.start()))
                        .append(",").append(fmt(overlay.end())).append(")'").append(label).append("; ");
                current = label;
                index++;
            }
            if (index > 0) {
                filter.append(chain);
                videoMap = current;
            }
        }
        args.addAll(List.of("-filter_complex", filter.toString(), "-map", videoMap, "-map", audioMap,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ar", "48000",
                "-ac", "2", "-movflags", "+faststart", output.toString()));
        return args;
    }

    /** 调节 + 特效 → ffmpeg 滤镜串（与前端 clipFilter 同口径）。空串 = 无效果。 */
    static String effectFilterOf(WorkflowFilmComposer.ClipStyle style) {
        if (style == null || style.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        double brightness = style.brightness() == null ? 0 : style.brightness() / 100.0;
        double contrast = style.contrast() == null ? 0 : style.contrast() / 100.0;
        double saturate = style.saturate() == null ? 0 : style.saturate() / 100.0;
        if (brightness != 0 || contrast != 0 || saturate != 0) {
            // eq: brightness ±0.5（±50 映射），contrast ≥0.5 倍基准，saturation ≥0 倍基准
            double eqBrightness = Math.max(-0.5, Math.min(0.5, brightness * 0.5));
            double eqContrast = Math.max(0.5, 1 + contrast);
            double eqSaturation = Math.max(0, 1 + saturate);
            parts.add(String.format(Locale.ROOT, "eq=brightness=%.3f:contrast=%.3f:saturation=%.3f",
                    eqBrightness, eqContrast, eqSaturation));
        }
        String effect = style.effect();
        if (effect != null && !effect.isBlank() && !"none".equals(effect)) {
            switch (effect) {
                case "mono" -> parts.add("colorchannelmixer=.3:.4:.3:0:.3:.4:.3:0:.3:.4:.3:0");
                case "vintage" -> parts.add(String.format(Locale.ROOT, "sepia=0.55,eq=contrast=1.12:saturation=0.85"));
                case "warm" -> parts.add(String.format(Locale.ROOT, "eq=saturation=1.45,sepia=0.28"));
                case "cool" -> parts.add(String.format(Locale.ROOT, "hue=h=-12:eq=saturation=1.1"));
                case "soft" -> parts.add(String.format(Locale.ROOT, "eq=brightness=0.08,gblur=sigma=0.5"));
                case "contrast" -> parts.add("eq=contrast=1.5");
                case "blur" -> parts.add("gblur=sigma=2");
                default -> { /* 未知特效 id 忽略，不阻断成片 */ }
            }
        }
        return String.join(",", parts);
    }

    /** 前端转场预设 id → xfade transition 名。未知 id 回落 fade（保守）。 */
    static String xfadeTransitionOf(String id) {
        return switch (id == null ? "fade" : id) {
            case "slide" -> "slideleft";
            case "zoom" -> "zoomin";
            case "wipe" -> "wipeleft";
            case "flash" -> "fadewhite";
            default -> "fade";
        };
    }

    private static void appendXfadeChain(StringBuilder filter, List<Clip> clips,
                                         List<WorkflowFilmComposer.ClipStyle> styles, List<Double> durations) {
        // xfade 折叠：offset_i = 已折叠时长 - 转场时长；每段折叠后时长 = 前累计 + 当前 - 转场时长
        double accumulated = durations.get(0);
        String current = "[v0]";
        for (int i = 1; i < clips.size(); i++) {
            WorkflowFilmComposer.ClipStyle style = styles != null ? styles.get(i - 1) : null;
            String transitionId = style == null ? null : style.transitionId();
            double seconds = style != null && style.transitionSeconds() != null
                    ? Math.max(0.1, Math.min(style.transitionSeconds(), Math.min(durations.get(i - 1), durations.get(i)) / 2))
                    : 0.5;
            double offset = Math.max(0, accumulated - seconds);
            String next = i == clips.size() - 1 ? "[vout]" : "[vx" + i + "]";
            if (transitionId == null) {
                // 无转场衔接：直接 concat 这一段（用 concat 滤镜接两路）
                filter.append(current).append("[v").append(i).append("]concat=n=2:v=1:a=0").append(next).append("; ");
                accumulated += durations.get(i);
            } else {
                filter.append(current).append("[v").append(i).append("]xfade=transition=")
                        .append(xfadeTransitionOf(transitionId))
                        .append(":duration=").append(fmt(seconds))
                        .append(":offset=").append(fmt(offset)).append(next).append("; ");
                accumulated = accumulated + durations.get(i) - seconds;
            }
            current = next;
        }
        if (clips.size() == 1) {
            filter.append("[v0]null[vout]; ");
        }
    }

    private static void appendAudioMix(StringBuilder filter, int base, List<AudioClip> tracks) {
        for (int i = 0; i < tracks.size(); i++) {
            AudioClip track = tracks.get(i);
            long delayMs = Math.max(0, Math.round(track.start() * 1000));
            filter.append("[").append(base + i).append(":a]atrim=start=").append(track.trimStart())
                    .append(":end=").append(track.trimEnd())
                    .append(",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=")
                    .append(track.muted() ? "0" : track.volume());
            if (delayMs > 0) {
                filter.append(",adelay=").append(delayMs).append("|").append(delayMs);
            }
            filter.append("[bg").append(i).append("]; ");
        }
        filter.append("[a]");
        for (int i = 0; i < tracks.size(); i++) {
            filter.append("[bg").append(i).append("]");
        }
        filter.append("amix=inputs=").append(tracks.size() + 1)
                .append(":duration=first:dropout_transition=0[aout]");
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
