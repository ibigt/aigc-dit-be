package cn.aigc.dit.generate.ffmpeg;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用本机 / 镜像内 ffmpeg。不可用时调用方自行降级。
 */
@Slf4j
public final class FfmpegSupport {

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private FfmpegSupport() {
    }

    public static boolean available() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 探测片长（秒）。ffprobe 优先；没有则从 {@code ffmpeg -i} 的 Duration 行解析。
     */
    public static Double durationSeconds(Path input) {
        if (input == null) {
            return null;
        }
        Double probed = probeDuration(input);
        if (probed != null && probed > 0) {
            return probed;
        }
        try {
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", input.toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(8, TimeUnit.SECONDS);
            return parseDurationSeconds(text);
        } catch (Exception e) {
            log.warn("ffmpeg duration failed, path={}", input, e);
            return null;
        }
    }

    static Double parseDurationSeconds(String ffmpegLog) {
        if (ffmpegLog == null || ffmpegLog.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION.matcher(ffmpegLog);
        if (!matcher.find()) {
            return null;
        }
        double seconds = Integer.parseInt(matcher.group(1)) * 3600
                + Integer.parseInt(matcher.group(2)) * 60
                + Double.parseDouble(matcher.group(3));
        return seconds > 0 ? seconds : null;
    }

    private static Double probeDuration(Path input) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    input.toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0 || text.isBlank()) {
                return null;
            }
            double seconds = Double.parseDouble(text);
            return seconds > 0 ? seconds : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void run(List<String> args) {
        run(args, 120);
    }

    public static void run(List<String> args, long timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.addAll(args);
        long timeout = timeoutSeconds < 1 ? 120 : timeoutSeconds;
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String logText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(ErrorCode.INTERNAL, "ffmpeg 超时");
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg failed: {}", logText);
                throw new BizException(ErrorCode.INTERNAL, "ffmpeg 执行失败");
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "无法启动 ffmpeg");
        }
    }

    public static Path tempFile(String prefix, String suffix) throws IOException {
        return java.nio.file.Files.createTempFile(prefix, suffix);
    }
}
