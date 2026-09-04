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

/**
 * 调用本机 / 镜像内 ffmpeg。不可用时调用方自行降级。
 */
@Slf4j
public final class FfmpegSupport {

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

    public static void run(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String logText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
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
