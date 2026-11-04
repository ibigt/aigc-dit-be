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
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-y"));
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
                log.warn("workflow ffmpeg failed: {}", Files.readString(logFile, StandardCharsets.UTF_8));
                throw new BizException(ErrorCode.INTERNAL, "ffmpeg 执行失败");
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

    @Override
    public FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips) {
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
        return new FilmOutput(key, posterKey, duration);
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
    public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips) {
        validate(userId, runId, nodeId, clips);
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
            Path output = FfmpegSupport.tempFile("dit-workflow-film-", ".mp4");
            temps.add(output);
            long timeout = generateProperties.getFfmpeg() == null ? 900
                    : generateProperties.getFfmpeg().getConcatEncodeTimeoutSeconds();
            try {
                runner.run(args(inputs, clips, hasAudio, output), timeout);
            } catch (BizException e) {
                if (e.getMessage() != null && e.getMessage().contains("超时")) {
                    throw new BizException(ErrorCode.INTERNAL, "工作流成片编码超时");
                }
                throw new BizException(ErrorCode.INTERNAL, "工作流成片编码失败");
            } catch (Exception e) {
                throw new BizException(ErrorCode.INTERNAL, "工作流成片编码失败");
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
            return new FilmOutput(key, posterKey, duration);
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
        return args(inputs, clips, java.util.Collections.nCopies(clips.size(), true), output);
    }

    static List<String> args(List<Path> inputs, List<Clip> clips, List<Boolean> hasAudio, Path output) {
        if (inputs.size() != clips.size() || hasAudio.size() != clips.size()) {
            throw new IllegalArgumentException("clip inputs mismatch");
        }
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
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            double duration = clip.trimEnd() - clip.trimStart();
            filter.append("[").append(i).append(":v]trim=start=").append(clip.trimStart())
                    .append(":end=").append(clip.trimEnd())
                    .append(",setpts=PTS-STARTPTS,fps=30,scale=1280:720:force_original_aspect_ratio=decrease,")
                    .append("pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v").append(i).append("]; ");
            filter.append("[").append(audioIndexes.get(i)).append(":a]");
            if (hasAudio.get(i)) {
                filter.append("atrim=start=").append(clip.trimStart()).append(":end=").append(clip.trimEnd());
            } else {
                filter.append("atrim=duration=").append(duration);
            }
            filter.append(",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=")
                    .append(clip.muted() ? "0" : clip.volume()).append("[a").append(i).append("]; ");
        }
        for (int i = 0; i < clips.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append("]");
        }
        filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[v][a]");
        args.addAll(List.of("-filter_complex", filter.toString(), "-map", "[v]", "-map", "[a]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ar", "48000",
                "-ac", "2", "-movflags", "+faststart", output.toString()));
        return args;
    }
}
