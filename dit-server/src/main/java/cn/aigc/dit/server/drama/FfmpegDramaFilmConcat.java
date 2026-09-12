package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegDramaFilmConcat implements DramaFilmConcat {

    private final GenerateProperties generateProperties;

    @Override
    public void concat(List<Path> inputs, Path output) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集还没有已出片镜头，无法拼接成片");
        }
        if (!FfmpegSupport.available()) {
            throw new BizException(ErrorCode.INTERNAL, "服务器未安装 ffmpeg，无法拼接成片");
        }
        Path listFile = null;
        try {
            listFile = FfmpegSupport.tempFile("dit-film-list-", ".txt");
            Files.writeString(listFile, concatList(inputs), StandardCharsets.UTF_8);
            GenerateProperties.Ffmpeg ffmpeg = generateProperties.getFfmpeg() == null
                    ? new GenerateProperties.Ffmpeg()
                    : generateProperties.getFfmpeg();
            try {
                FfmpegSupport.run(copyArgs(listFile, output), ffmpeg.getConcatCopyTimeoutSeconds());
            } catch (BizException e) {
                if (e.getMessage() != null && e.getMessage().contains("超时")) {
                    throw e;
                }
                log.warn("film concat copy failed, fallback to re-encode: {}", e.getMessage());
                Files.deleteIfExists(output);
                FfmpegSupport.run(encodeArgs(listFile, output), ffmpeg.getConcatEncodeTimeoutSeconds());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "拼接成片失败");
        } finally {
            deleteQuietly(listFile);
        }
    }

    static String concatList(List<Path> inputs) {
        StringBuilder text = new StringBuilder();
        for (Path input : inputs) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("file '").append(escapeConcatPath(input)).append('\'');
        }
        return text.toString();
    }

    static String escapeConcatPath(Path path) {
        String abs = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return abs.replace("'", "'\\''");
    }

    private static List<String> copyArgs(Path listFile, Path output) {
        List<String> args = new ArrayList<>();
        args.add("-f");
        args.add("concat");
        args.add("-safe");
        args.add("0");
        args.add("-i");
        args.add(listFile.toString());
        args.add("-c");
        args.add("copy");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());
        return args;
    }

    private static List<String> encodeArgs(Path listFile, Path output) {
        List<String> args = new ArrayList<>();
        args.add("-f");
        args.add("concat");
        args.add("-safe");
        args.add("0");
        args.add("-i");
        args.add(listFile.toString());
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("18");
        args.add("-c:a");
        args.add("aac");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());
        return args;
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
