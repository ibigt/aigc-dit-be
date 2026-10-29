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
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegDramaFilmConcat implements DramaFilmConcat {

    /** 成片统一帧率。短剧出片常见 24fps，硬切前先对齐。 */
    static final int FPS = 24;

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

    @Override
    public void concatSmooth(List<Path> inputs, List<DramaFilmModes.ClipSpan> spans, String aspectRatio, Path output) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集还没有已出片镜头，无法拼接成片");
        }
        if (spans == null || spans.size() != inputs.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "成片裁切与镜头数量不一致");
        }
        if (!FfmpegSupport.available()) {
            throw new BizException(ErrorCode.INTERNAL, "服务器未安装 ffmpeg，无法拼接成片");
        }
        try {
            List<Boolean> hasAudio = new ArrayList<>(inputs.size());
            for (Path input : inputs) {
                hasAudio.add(hasAudio(input));
            }
            GenerateProperties.Ffmpeg ffmpeg = generateProperties.getFfmpeg() == null
                    ? new GenerateProperties.Ffmpeg()
                    : generateProperties.getFfmpeg();
            FfmpegSupport.run(
                    smoothArgs(inputs, spans, aspectRatio, hasAudio, output),
                    ffmpeg.getConcatEncodeTimeoutSeconds());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("smooth film concat failed", e);
            throw new BizException(ErrorCode.INTERNAL, "拼接成片失败");
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

    /**
     * 顺接必须重编码。画面硬切；音轨在接点两侧各做短淡化，避免 acrossfade 把声音缩得比画面短。
     * 缺音轨补 48k 立体声静音。画幅按系列比例 scale + pad，不用横屏硬套。
     */
    static List<String> smoothArgs(List<Path> inputs, List<DramaFilmModes.ClipSpan> spans, String aspectRatio,
                                    List<Boolean> hasAudio, Path output) {
        if (inputs.size() != spans.size() || hasAudio.size() != spans.size()) {
            throw new IllegalArgumentException("clip inputs mismatch");
        }
        int[] frame = frameSize(aspectRatio);
        int width = frame[0];
        int height = frame[1];
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        for (Path input : inputs) {
            args.add("-i");
            args.add(input.toString());
        }
        int silentCursor = inputs.size();
        List<Integer> audioIndex = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            if (Boolean.TRUE.equals(hasAudio.get(i))) {
                audioIndex.add(i);
                continue;
            }
            audioIndex.add(silentCursor++);
            args.add("-f");
            args.add("lavfi");
            args.add("-t");
            args.add(seconds(spans.get(i).keptSec()));
            args.add("-i");
            args.add("anullsrc=channel_layout=stereo:sample_rate=48000");
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            DramaFilmModes.ClipSpan span = spans.get(i);
            double start = span.headSec();
            double end = span.endSec();
            double kept = span.keptSec();
            filter.append('[').append(i).append(":v]trim=start=").append(seconds(start))
                    .append(":end=").append(seconds(end))
                    .append(",setpts=PTS-STARTPTS,fps=").append(FPS)
                    .append(",scale=").append(width).append(':').append(height)
                    .append(":force_original_aspect_ratio=decrease,pad=")
                    .append(width).append(':').append(height)
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v").append(i).append("];");
            filter.append('[').append(audioIndex.get(i)).append(":a]");
            if (Boolean.TRUE.equals(hasAudio.get(i))) {
                filter.append("atrim=start=").append(seconds(start)).append(":end=").append(seconds(end));
            } else {
                filter.append("atrim=duration=").append(seconds(kept));
            }
            filter.append(",asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo");
            appendEdgeFades(filter, i, spans.size(), kept);
            filter.append("[a").append(i).append("];");
        }
        for (int i = 0; i < spans.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append("concat=n=").append(spans.size()).append(":v=1:a=1[v][a]");
        args.add("-filter_complex");
        args.add(filter.toString());
        args.add("-map");
        args.add("[v]");
        args.add("-map");
        args.add("[a]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("18");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-c:a");
        args.add("aac");
        args.add("-ar");
        args.add("48000");
        args.add("-ac");
        args.add("2");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toString());
        return args;
    }

    static int[] frameSize(String aspectRatio) {
        String ratio = aspectRatio == null ? "" : aspectRatio.trim().replace('：', ':');
        return switch (ratio) {
            case "9:16" -> new int[] {720, 1280};
            case "3:4" -> new int[] {720, 960};
            case "1:1" -> new int[] {720, 720};
            case "4:3" -> new int[] {960, 720};
            case "21:9" -> new int[] {1680, 720};
            default -> new int[] {1280, 720};
        };
    }

    private static void appendEdgeFades(StringBuilder filter, int index, int count, double kept) {
        double fade = DramaFilmModes.AUDIO_EDGE_FADE_SEC;
        boolean fadeIn = index > 0;
        boolean fadeOut = index < count - 1;
        int edges = (fadeIn ? 1 : 0) + (fadeOut ? 1 : 0);
        if (edges == 0 || kept <= fade * edges + 0.05D) {
            return;
        }
        if (fadeIn) {
            filter.append(",afade=t=in:st=0:d=").append(seconds(fade));
        }
        if (fadeOut) {
            filter.append(",afade=t=out:st=").append(seconds(Math.max(0D, kept - fade)))
                    .append(":d=").append(seconds(fade));
        }
    }

    private static String seconds(double value) {
        double rounded = Math.round(Math.max(0D, value) * 1000D) / 1000D;
        return String.format(Locale.ROOT, "%.3f", rounded);
    }

    private static boolean hasAudio(Path input) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-select_streams", "a",
                    "-show_entries", "stream=index", "-of", "csv=p=0",
                    input.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(ErrorCode.INTERNAL, "探测音轨超时");
            }
            if (process.exitValue() != 0) {
                throw new BizException(ErrorCode.INTERNAL, "探测音轨失败");
            }
            return !output.isBlank();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BizException(ErrorCode.INTERNAL, "探测音轨失败");
        }
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
