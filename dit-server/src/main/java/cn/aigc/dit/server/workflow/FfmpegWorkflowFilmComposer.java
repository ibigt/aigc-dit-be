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
                                   List<AudioClip> audioClips, List<WorkflowFilmComposer.ClipStyle> styles,
                                   List<WorkflowFilmComposer.OverlayClip> overlayClips,
                                   List<WorkflowFilmComposer.PipClip> pipClips) {
        // PiP 参数写进 step.pipClips，由 WorkflowStepFingerprint 驱动重合成。
        // 但 findExisting 只看 filmKey 是否已有可播放成片，不校验画中画是否烧进去了：
        // 一旦崩溃恢复时复用了「同 run 早期无 PiP」的成片，画中画会静默丢失。
        // 因此带 PiP 时一律不复用，直接重合成（正常流程只会进一次 EDITING，开销可接受）。
        if (pipClips != null && !pipClips.isEmpty()) {
            return null;
        }
        return findExisting(userId, runId, nodeId, clips, target, audioClips);
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

    /** 图片素材判定：按 storageKey 扩展名（与前端 mediaKindOf 同口径）。 */
    private static boolean isImageKey(String key) {
        return key != null && key.matches("(?i).*\\.(png|jpe?g|webp|gif)$");
    }

    /** 图片素材的临时文件后缀（ffmpeg 按内容识别格式，后缀仅为可读性）。 */
    private static String imageSuffix(String key) {
        int dot = key == null ? -1 : key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot) : ".png";
    }

    private static boolean playableMp4(Path film) {        try (InputStream input = Files.newInputStream(film)) {
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
        return compose(userId, runId, nodeId, clips, target, audioClips, styles, overlayClips, List.of());
    }

    @Override
    public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target,
                              List<AudioClip> audioClips, List<WorkflowFilmComposer.ClipStyle> styles,
                              List<WorkflowFilmComposer.OverlayClip> overlayClips,
                              List<WorkflowFilmComposer.PipClip> pipClips) {
        // V2 画中画：video 类叠加不走 PNG 管线，分流出去（OverlayRenderer 只处理 text/graphic）
        List<WorkflowFilmComposer.OverlayClip> overlays = overlayClips == null ? List.of()
                : overlayClips.stream().filter(clip -> clip != null && !"video".equals(clip.kind())).toList();
        List<WorkflowFilmComposer.PipClip> pips = pipClips == null ? List.of() : pipClips;
        validate(userId, runId, nodeId, clips);
        List<WorkflowFilmComposer.ClipStyle> clipStyles = styles == null ? null
                : (styles.size() == clips.size() ? styles : null);
        for (WorkflowFilmComposer.PipClip pip : pips) {
            if (pip == null || pip.sourceKey() == null || pip.sourceKey().isBlank()
                    || !Double.isFinite(pip.start()) || !Double.isFinite(pip.end()) || pip.end() <= pip.start()
                    || !Double.isFinite(pip.trimStart()) || !Double.isFinite(pip.trimEnd())
                    || pip.trimStart() < 0 || pip.trimEnd() <= pip.trimStart()
                    || !Double.isFinite(pip.scale()) || pip.scale() <= 0 || pip.scale() > 100
                    || !Double.isFinite(pip.volume()) || pip.volume() < 0 || pip.volume() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "画中画片段参数无效");
            }
        }
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
            List<Clip> normalized = new ArrayList<>();
            for (Clip clip : clips) {
                Path input = FfmpegSupport.tempFile("dit-workflow-clip-", ".mp4");
                temps.add(input);
                // 图片素材（png/jpg/webp/gif）：先转成 trimEnd-trimStart 时长的静帧 mp4，
                // 之后与视频片段完全同链路（Clip/args 零改动）。图片没有「源时长」概念，
                // trimEnd 即展示时长，不做超界校验。
                if (isImageKey(clip.storageKey())) {
                    try {
                        Path image = FfmpegSupport.tempFile("dit-workflow-image-", imageSuffix(clip.storageKey()));
                        temps.add(image);
                        try (InputStream source = storage.get(clip.storageKey())) {
                            Files.copy(source, image, StandardCopyOption.REPLACE_EXISTING);
                        }
                        double stillSeconds = Math.max(0.1, clip.trimEnd() - clip.trimStart());
                        FfmpegSupport.run(List.of("-hide_banner", "-loglevel", "error",
                                "-loop", "1", "-i", image.toString(),
                                "-t", Double.toString(stillSeconds),
                                "-r", "30", "-pix_fmt", "yuv420p",
                                "-c:v", "libx264", "-y", input.toString()), 120);
                    } catch (Exception e) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "图片素材无法转为视频段落");
                    }
                    hasAudio.add(false);
                    normalized.add(clip);
                    inputs.add(input);
                    continue;
                }
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
                normalized.add(clip);
                inputs.add(input);
            }
            clips = normalized;
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
            // V2 画中画：PiP 素材落盘临时文件（与 clips 同口径：裁剪钳制到真实时长，整段不可用静默丢弃）。
            // 未静音且探针确认有音轨才进 amix；静音、图片静帧或无音轨只叠画面。
            List<Path> pipInputs = new ArrayList<>();
            List<WorkflowFilmComposer.PipClip> usablePips = new ArrayList<>();
            List<Boolean> pipHasAudio = new ArrayList<>();
            for (WorkflowFilmComposer.PipClip pip : pips) {
                Path input = FfmpegSupport.tempFile("dit-workflow-pip-", ".mp4");
                temps.add(input);
                try {
                    try (InputStream source = storage.get(pip.sourceKey())) {
                        Files.copy(source, input, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new BizException(ErrorCode.INTERNAL, "读取画中画素材失败");
                }
                Double pipDuration = FfmpegSupport.durationSeconds(input);
                if (pipDuration == null || !Double.isFinite(pipDuration) || pipDuration <= 0) {
                    log.warn("pip source has no valid duration, skipped: {}", pip.sourceKey());
                    continue;
                }
                double clampedStart = Math.min(pip.trimStart(), Math.max(0, pipDuration - 0.01));
                double clampedEnd = Math.min(pip.trimEnd(), pipDuration);
                if (clampedEnd <= clampedStart) {
                    continue;
                }
                WorkflowFilmComposer.PipClip usable = clampedStart == pip.trimStart() && clampedEnd == pip.trimEnd()
                        ? pip
                        : new WorkflowFilmComposer.PipClip(pip.start(), pip.end(), pip.sourceKey(),
                                clampedStart, clampedEnd, pip.x(), pip.y(), pip.scale(), pip.opacity(),
                                pip.muted(), pip.volume());
                boolean mixAudio = false;
                if (!usable.muted() && !isImageKey(usable.sourceKey())) {
                    try {
                        mixAudio = runner.hasAudio(input);
                    } catch (Exception e) {
                        throw new BizException(ErrorCode.INTERNAL, "读取画中画素材失败");
                    }
                }
                usablePips.add(usable);
                pipInputs.add(input);
                pipHasAudio.add(mixAudio);
            }
            Path output = FfmpegSupport.tempFile("dit-workflow-film-", ".mp4");
            temps.add(output);
            // 叠加层渲染（排期 #7）：文字/图形每段烧成**独立**的透明 PNG。
            // 必须一段一张：多段文字若挤在同一张图里，各段的时间窗会互相串
            // （同一张图里所有文字都在，只是按不同 enable 区间显示）。
            List<Path> overlayPngs = new ArrayList<>();
            if (overlays != null && !overlays.isEmpty()) {
                int ow = target == null ? DEFAULT_WIDTH : target.width();
                int oh = target == null ? DEFAULT_HEIGHT : target.height();
                for (WorkflowFilmComposer.OverlayClip clip : overlays) {
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
                        clipStyles, overlayPngs.isEmpty() ? null : overlayPngs.get(0), overlays, overlayPngs,
                        usablePips, pipInputs, pipHasAudio), timeout);
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
        return args(inputs, clips, hasAudio, target, output, audioClips, audioInputs, styles, overlayPng,
                overlays, overlayPngPaths, List.of(), List.of());
    }

    /**
     * V2 画中画全量构造：pipClips 与 pipInputs 一一对应（本地临时文件）。
     * 每路 PiP：trim → setpts → fps → scale（成片宽 × scale%）→ format，
     * 再按 enable 时间窗 overlay 到主画面（位置 x/y 百分比换算成像素，不加 shortest=1）。
     * pipHasAudio 为 null 时不混副轨原声，filter 与不传该参数逐字节一致。
     * pipClips 为空时 filter 与十一参重载逐字节一致。
     */
    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target,
                             Path output, List<AudioClip> audioClips, List<Path> audioInputs,
                             List<WorkflowFilmComposer.ClipStyle> styles, Path overlayPng,
                             List<WorkflowFilmComposer.OverlayClip> overlays,
                             List<Path> overlayPngPaths,
                             List<WorkflowFilmComposer.PipClip> pipClips, List<Path> pipInputs) {
        return args(inputs, clips, hasAudio, target, output, audioClips, audioInputs, styles, overlayPng,
                overlays, overlayPngPaths, pipClips, pipInputs, null);
    }

    /**
     * 带副轨原声探针的画中画构造：pipHasAudio 与 pipClips 同序。
     * 未静音且对应项为 true 时，该路做 atrim、adelay（对齐成片内 start）、volume，再并入 amix。
     * 静音或无音轨的 PiP 不进 amix。
     */
    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, FilmSize target,
                             Path output, List<AudioClip> audioClips, List<Path> audioInputs,
                             List<WorkflowFilmComposer.ClipStyle> styles, Path overlayPng,
                             List<WorkflowFilmComposer.OverlayClip> overlays,
                             List<Path> overlayPngPaths,
                             List<WorkflowFilmComposer.PipClip> pipClips, List<Path> pipInputs,
                             List<Boolean> pipHasAudio) {
        if (inputs.size() != clips.size() || hasAudio.size() != clips.size()) {
            throw new IllegalArgumentException("clip inputs mismatch");
        }
        List<AudioClip> tracks = audioClips == null ? List.of() : audioClips;
        if (audioInputs != null && audioInputs.size() != tracks.size()) {
            throw new IllegalArgumentException("audio inputs mismatch");
        }
        List<WorkflowFilmComposer.PipClip> pips = pipClips == null ? List.of() : pipClips;
        if (pipInputs != null && pipInputs.size() != pips.size()) {
            throw new IllegalArgumentException("pip inputs mismatch");
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
        // 叠加层 PNG 输入：追加在所有输入之后，每段一张（与 overlays 同序）。
        // -loop 1 必加：单帧 PNG 在 enable 窗口起点 > 0 时（如 between(t,0.3,3)），t<start 期间
        // overlay 不消费副输入但流已 EOF，窗口开启后无帧可叠、字幕静默丢失（ffmpeg 9.0.2 实测）。
        // 与之配套，下方每级 overlay 必须带 shortest=1，否则无限 PNG 流会把成片拖成永不结束。
        int overlayInput = silentInput + tracks.size();
        for (Path png : overlayFiles) {
            args.add("-loop");
            args.add("1");
            args.add("-i");
            args.add(png.toString());
        }
        // V2 画中画输入：追加在叠加 PNG 之后（与 pipClips 同序）
        int pipInput = overlayInput + overlayFiles.size();
        for (Path pip : (pipInputs == null ? List.<Path>of() : pipInputs)) {
            args.add("-i");
            args.add(pip.toString());
        }
        List<Integer> pipAudio = pipAudioIndexes(pips, pipHasAudio);
        StringBuilder filter = new StringBuilder();
        // 每路输入：trim → setpts → fps → scale → pad → [eq/hue/gblur 样式滤镜] → setsar → format
        // 无样式片段的链与旧版完全一致（仅当 styled 才插入滤镜段，保持逐字节兼容）。
        List<Double> durations = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            double duration = clip.trimEnd() - clip.trimStart();
            durations.add(duration);
            String effectFilters = styled && clipStyles.get(i) != null
                    ? effectFilterOf(clipStyles.get(i), width) : "";
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
        // 未静音且有音轨的 PiP 同样 atrim → volume → adelay（对齐成片内 start）后并入同一路 amix。
        // duration=first 以视频拼接结果为准，超出成片尾部的音频被截断，与预览语义一致。
        // 无音轨且无 PiP 原声时 concat 直接产出 [v][a]（与旧版输出完全一致）。
        // PiP 原声链必须写在 concat / xfade 收尾之后，不能插进 concat 的输入标签中间。
        String audioMap;
        boolean xfade = hasTransitions;
        if (tracks.isEmpty() && !xfade) {
            filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]; ");
            if (pipAudio.isEmpty()) {
                audioMap = "[a]";
            } else {
                appendPipAudioChains(filter, pipInput, pips, pipAudio);
                appendAudioMix(filter, silentInput, tracks, pipAudio);
                audioMap = "[aout]";
            }
        } else if (!xfade) {
            filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]; ");
            appendPipAudioChains(filter, pipInput, pips, pipAudio);
            appendAudioMix(filter, silentInput, tracks, pipAudio);
            audioMap = "[aout]";
        } else {
            // 转场路径：视频 xfade 折叠出 [vout]；音频 concat 出 [a]（或 amix 出 [aout]）
            filter.append("concat=n=").append(clips.size()).append(":v=0:a=1[a]");
            appendXfadeChain(filter, clips, clipStyles, durations);
            if (tracks.isEmpty() && pipAudio.isEmpty()) {
                audioMap = "[a]";
            } else if (pipAudio.isEmpty()) {
                // amix 的第一路要接 [vout] 对应不了——amix 只吃音频；视频 [vout] 单独 map。
                // 这里音频轨与 [a] amix（把 [a] 作为 amix 第一输入）。
                filter.append("[a]");
                for (int i = 0; i < tracks.size(); i++) {
                    filter.append("[bg").append(i).append("]");
                }
                filter.append("amix=inputs=").append(tracks.size() + 1)
                        .append(":duration=first:dropout_transition=0[aout]");
                audioMap = "[aout]";
            } else {
                if (filter.length() > 0 && !filter.toString().endsWith("; ")) {
                    filter.append("; ");
                }
                appendPipAudioChains(filter, pipInput, pips, pipAudio);
                appendAudioMix(filter, silentInput, tracks, pipAudio);
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
                // shortest=1 必加：-loop 1 让 PNG 变成无限流，overlay 默认 eof_action=repeat 会在
                // 主画面 EOF 后继续重复最后一帧，成片永不结束（实测 timeout）。shortest=1 让该级
                // overlay 随主画面（而非 PNG）结束，保证成片时长严格等于拼接结果且不拖尾。
                chain.append(current).append("[").append(overlayInput + i).append(":v]")
                        .append("overlay=enable='between(t,").append(fmt(overlay.start()))
                        .append(",").append(fmt(overlay.end())).append(")'").append(":shortest=1")
                        .append(label).append("; ");
                current = label;
                index++;
            }
            if (index > 0) {
                // amix / xfade 分支结尾的 [aout]（或 [a]）后面没有分号，直接接 overlay 链会拼成
                // 「[aout][v][3:v]overlay=…」一句导致 filtergraph 解析失败（Trailing garbage after a filter）。
                // 拼接前确保前文以 "; " 收尾；分号结尾对 ffmpeg 恒安全。
                if (filter.length() > 0 && !filter.toString().endsWith("; ")) {
                    filter.append("; ");
                }
                filter.append(chain);
                videoMap = current;
            }
        }
        // V2 画中画：每路 PiP 先单独处理（trim → fps → scale 到成片宽×scale%），再串接 overlay。
        // 位置 x/y 是画面百分比中心点，换算成 overlay 的左上角像素坐标（x%×W - w/2）。
        // 注意 overlay 的坐标接受 main_w/overlay_w 表达式，直接用表达式避免自己算。
        if (!pips.isEmpty()) {
            // 前置链可能以 [aout]/[a] 结尾（amix / xfade 分支没有分号），若直接追加 [N:v]trim=…
            // 会拼成一条链（Trailing garbage after a filter）。分号结尾对 ffmpeg 恒安全。
            if (filter.length() > 0 && !filter.toString().endsWith("; ")) {
                filter.append("; ");
            }
            StringBuilder pipChain = new StringBuilder();
            for (int i = 0; i < pips.size(); i++) {
                WorkflowFilmComposer.PipClip pip = pips.get(i);
                if (pip == null) continue;
                // PiP 分支链：trim（源内裁剪）→ setpts → fps → scale=成片宽×scale%:auto → format
                int pw = Math.max(2, (int) Math.round(width * pip.scale() / 100.0 / 2.0) * 2); // 偶数化
                double opacity = Math.max(0, Math.min(1, pip.opacity() / 100.0));
                boolean translucent = opacity < 1;
                filter.append("[").append(pipInput + i).append(":v]trim=start=").append(fmt(pip.trimStart()))
                        .append(":end=").append(fmt(pip.trimEnd()))
                        .append(",setpts=PTS-STARTPTS,fps=30,scale=").append(pw).append(":-2");
                if (translucent) {
                    // 半透明必须保留 alpha：yuv420p 无 alpha 通道，colorchannelmixer 改完再转 yuv420p
                    // 会把 alpha 整层丢掉，overlay 仍按不透明处理。改用 yuva420p + overlay=format=auto。
                    filter.append(",format=rgba,colorchannelmixer=aa=")
                            .append(String.format(Locale.ROOT, "%.2f", opacity))
                            .append(",format=yuva420p");
                } else {
                    filter.append(",format=yuv420p");
                }
                filter.append("[pipv").append(i).append("]; ");
            }
            String current = videoMap;
            for (int i = 0; i < pips.size(); i++) {
                WorkflowFilmComposer.PipClip pip = pips.get(i);
                if (pip == null) continue;
                String label = "[pip" + i + "]";
                pipChain.append(current).append("[pipv").append(i).append("]")
                        .append("overlay=format=auto:x=")
                        .append(String.format(Locale.ROOT, "'round(main_w*%.4f-overlay_w/2)'", Math.max(0, Math.min(100, pip.x())) / 100.0))
                        .append(":y=")
                        .append(String.format(Locale.ROOT, "'round(main_h*%.4f-overlay_h/2)'", Math.max(0, Math.min(100, pip.y())) / 100.0))
                        .append(":enable='between(t,").append(fmt(pip.start()))
                        .append(",").append(fmt(pip.end())).append(")'").append(label).append("; ");
                current = label;
            }
            if (!pipChain.isEmpty()) {
                if (filter.length() > 0 && !filter.toString().endsWith("; ")) {
                    filter.append("; ");
                }
                filter.append(pipChain);
                videoMap = current;
            }
        }
        args.addAll(List.of("-filter_complex", filter.toString(), "-map", videoMap, "-map", audioMap,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ar", "48000",
                "-ac", "2", "-movflags", "+faststart", output.toString()));
        return args;
    }

    /**
     * 调节 + 特效 → ffmpeg 滤镜串（与前端 clipFilter 同口径）。空串 = 无效果。
     *
     * <p>模糊半径与字号同理（14.51 口径）：前端预览的 CSS blur() 作用在 ≤560px 宽的预览器上，
     * sigma 必须按「成片宽 / 预览基准宽」等比放大，否则 1080P 成片的模糊只有预览的 ~1/3.4。
     */
    static String effectFilterOf(WorkflowFilmComposer.ClipStyle style, int width) {
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
        // 预览 CSS px → 成片 px：模糊半径随画幅等比放大（基准同 OverlayRenderer.PREVIEW_BASE_WIDTH）
        double blurScale = width / OverlayRenderer.PREVIEW_BASE_WIDTH;
        String effect = style.effect();
        if (effect != null && !effect.isBlank() && !"none".equals(effect)) {
            switch (effect) {
                case "mono" -> parts.add("colorchannelmixer=.3:.4:.3:0:.3:.4:.3:0:.3:.4:.3:0");
                case "vintage" -> parts.add(String.format(Locale.ROOT, "sepia=0.55,eq=contrast=1.12:saturation=0.85"));
                case "warm" -> parts.add(String.format(Locale.ROOT, "eq=saturation=1.45,sepia=0.28"));
                case "cool" -> parts.add(String.format(Locale.ROOT, "hue=h=-12:eq=saturation=1.1"));
                case "soft" -> parts.add(String.format(Locale.ROOT, "eq=brightness=0.08,gblur=sigma=%.2f", 0.5 * blurScale));
                case "contrast" -> parts.add("eq=contrast=1.5");
                case "blur" -> parts.add(String.format(Locale.ROOT, "gblur=sigma=%.2f", 2 * blurScale));
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

    /**
     * 未静音且探针确认有音轨的 PiP。静音、无音轨、或 pipHasAudio 为 null 时返回空列表（不进 amix）。
     */
    private static List<Integer> pipAudioIndexes(List<WorkflowFilmComposer.PipClip> pips,
                                                List<Boolean> pipHasAudio) {
        if (pipHasAudio == null) {
            return List.of();
        }
        if (pipHasAudio.size() != pips.size()) {
            throw new IllegalArgumentException("pip audio mismatch");
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < pips.size(); i++) {
            WorkflowFilmComposer.PipClip pip = pips.get(i);
            if (pip == null || pip.muted() || !Boolean.TRUE.equals(pipHasAudio.get(i))) {
                continue;
            }
            indexes.add(i);
        }
        return indexes;
    }

    /** PiP 原声：atrim 裁源内区间，volume 用片段音量，adelay 对齐成片内 start（0 则省略）。 */
    private static void appendPipAudioChains(StringBuilder filter, int pipInput,
                                            List<WorkflowFilmComposer.PipClip> pips,
                                            List<Integer> pipAudio) {
        for (int index : pipAudio) {
            WorkflowFilmComposer.PipClip pip = pips.get(index);
            long delayMs = Math.max(0, Math.round(pip.start() * 1000));
            filter.append("[").append(pipInput + index).append(":a]atrim=start=").append(pip.trimStart())
                    .append(":end=").append(pip.trimEnd())
                    .append(",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=")
                    .append(pip.volume());
            if (delayMs > 0) {
                filter.append(",adelay=").append(delayMs).append("|").append(delayMs);
            }
            filter.append("[pipa").append(index).append("]; ");
        }
    }

    private static void appendAudioMix(StringBuilder filter, int base, List<AudioClip> tracks,
                                      List<Integer> pipAudio) {
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
        for (int index : pipAudio) {
            filter.append("[pipa").append(index).append("]");
        }
        filter.append("amix=inputs=").append(tracks.size() + pipAudio.size() + 1)
                .append(":duration=first:dropout_transition=0[aout]");
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
