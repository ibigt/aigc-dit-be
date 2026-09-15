package cn.aigc.dit.generate.audio;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Limits reference audio to a Seedance-safe duration and normalizes its encoding.
 */
@Slf4j
@Component
public class FfmpegAudioReferenceProcessor implements AudioReferenceProcessor {

    @Override
    public ProcessedAudio process(byte[] source, String sourceName, long maxDurationMillis) {
        if (source == null || source.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考音频文件为空");
        }
        if (maxDurationMillis <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考音频时长限制无效");
        }
        if (!FfmpegSupport.available()) {
            throw new BizException(ErrorCode.INTERNAL, "参考音频处理服务不可用，请联系管理员");
        }

        Path input = null;
        Path output = null;
        try {
            input = FfmpegSupport.tempFile("dit-audio-src-", suffix(sourceName));
            output = FfmpegSupport.tempFile("dit-audio-ref-", ".mp3");
            Files.write(input, source);
            FfmpegSupport.run(commandArgs(input, output, maxDurationMillis));
            byte[] processed = Files.readAllBytes(output);
            if (processed.length == 0) {
                throw new BizException(ErrorCode.INTERNAL, "参考音频处理结果为空");
            }
            return new ProcessedAudio(processed, "audio/mpeg");
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.warn("reference audio processing failed, source={}", sourceName, e);
            throw new BizException(ErrorCode.INTERNAL, "参考音频处理失败");
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    static List<String> commandArgs(Path input, Path output, long maxDurationMillis) {
        return List.of(
                    "-i", input.toString(),
                    "-t", seconds(maxDurationMillis),
                    "-vn",
                    "-ac", "1",
                    "-ar", "24000",
                    "-codec:a", "libmp3lame",
                    "-b:a", "128k",
                    output.toString());
    }

    private static String seconds(long durationMillis) {
        long seconds = durationMillis / 1000;
        long millis = durationMillis % 1000;
        if (millis == 0) {
            return Long.toString(seconds);
        }
        return seconds + "." + String.format(Locale.ROOT, "%03d", millis).replaceFirst("0+$", "");
    }

    private static String suffix(String sourceName) {
        if (sourceName == null) {
            return ".audio";
        }
        String lower = sourceName.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".webm")) {
            if (lower.endsWith(suffix)) {
                return suffix;
            }
        }
        return ".audio";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary cleanup only.
        }
    }
}
