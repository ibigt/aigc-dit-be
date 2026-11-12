package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.LocalObjectStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.aigc.dit.server.workflow.WorkflowFilmComposer.Clip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegWorkflowFilmComposerTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"", "corrupt encoded bytes"})
    void refusesFreshEmptyOrCorruptOutputBeforeStorage(String encoded) throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("source.mp4", Files.readAllBytes(validMp4()), "video/mp4");
        var runner = new FfmpegWorkflowFilmComposer.Runner() {
            public boolean available() { return true; }
            public boolean hasAudio(Path input) { return false; }
            public void run(List<String> args, long timeout) throws Exception { Files.writeString(Path.of(args.get(args.size() - 1)), encoded); }
        };
        var composer = new FfmpegWorkflowFilmComposer(storage, key -> null, new GenerateProperties(), runner);
        assertThrows(BizException.class, () -> composer.compose(7L, 31L, "merge", List.of(new Clip("source.mp4", 0, .3, false, 1))));
        assertFalse(Files.exists(directory.resolve("objects/users/7/workflows/31/merge/film.mp4")));
    }

    @Test
    void rejectsTrimPastProbedSourceDurationBeforeEncoding() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("source.mp4", Files.readAllBytes(validMp4()), "video/mp4");
        var encoded = new java.util.concurrent.atomic.AtomicBoolean();
        var runner = new FfmpegWorkflowFilmComposer.Runner() {
            public boolean available() { return true; }
            public boolean hasAudio(Path input) { return false; }
            public void run(List<String> args, long timeout) throws Exception { encoded.set(true); Files.copy(validMp4(), Path.of(args.get(args.size() - 1)), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        };
        var composer = new FfmpegWorkflowFilmComposer(storage, key -> null, new GenerateProperties(), runner);
        assertThrows(BizException.class, () -> composer.compose(7L, 31L, "merge", List.of(new Clip("source.mp4", 0, 2, false, 1))));
        assertFalse(encoded.get());
        assertFalse(Files.exists(directory.resolve("objects/users/7/workflows/31/merge/film.mp4")));
    }

    @Test
    void recoversPlayableStoredMp4AndPosterWithoutEncodingAgain() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        String key = "users/7/workflows/31/merge/film.mp4";
        storage.put(key, Files.readAllBytes(validMp4()), "video/mp4");
        storage.put("users/7/workflows/31/merge/film-poster.webp", "poster".getBytes(), "image/webp");
        FakeRunner runner = new FakeRunner();
        FfmpegWorkflowFilmComposer composer = composer(storage, runner);
        List<Clip> clips = List.of(new Clip("source.mp4", 1, 4, false, 1));

        WorkflowFilmComposer.FilmOutput existing = composer.findExisting(7L, 31L, "merge", clips);

        assertEquals(key, existing.storageKey());
        assertEquals("users/7/workflows/31/merge/film-poster.webp", existing.posterKey());
        assertEquals(3, existing.durationSeconds());
        assertEquals(0, runner.commands.size());
        assertNull(composer.findExisting(7L, 32L, "merge", clips));
    }

    @Test
    void refusesCorruptStoredFilm() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        String key = "users/7/workflows/31/merge/film.mp4";
        storage.put(key, "assembled".getBytes(), "video/mp4");
        FfmpegWorkflowFilmComposer composer = composer(storage, new FakeRunner());

        assertNull(composer.findExisting(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, 3, false, 1))));
    }

    @Test
    void refusesTruncatedMp4EvenWhenHeaderExists() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        byte[] bytes = Files.readAllBytes(validMp4());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("users/7/workflows/31/merge/film.mp4",
                java.util.Arrays.copyOf(bytes, bytes.length / 2), "video/mp4");

        assertNull(composer(storage, new FakeRunner()).findExisting(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, .4, false, 1))));
    }

    @Test
    void refusesPlayableQuickTimeMovAtMp4RecoveryKey() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path source = directory.resolve("valid.mov");
        Process fixture = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=32x32:r=10", "-t", "0.4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mov", source.toString()).start();
        assertTrue(fixture.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, fixture.exitValue());
        byte[] bytes = Files.readAllBytes(source);
        assertEquals("ftyp", new String(bytes, 4, 4, StandardCharsets.US_ASCII));
        assertEquals("qt  ", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("users/7/workflows/31/merge/film.mp4", bytes, "video/mp4");

        assertNull(composer(storage, new FakeRunner()).findExisting(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, .4, false, 1))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zzzz", "iso0", "isoA", "mp40", "3gp4"})
    void refusesPlayableFilmWithoutRecognizedMp4MajorBrand(String brand) throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path source = validMp4("-brand", brand);
        byte[] bytes = Files.readAllBytes(source);
        assertEquals("ftyp", new String(bytes, 4, 4, StandardCharsets.US_ASCII));
        assertEquals(brand, new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        assertTrue(FfmpegSupport.durationSeconds(source) > 0);
        Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames",
                "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames:format=format_name",
                "-of", "default=noprint_wrappers=1", source.toString()).redirectErrorStream(true).start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, probe.exitValue());
        String probeOutput = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(probeOutput.contains("nb_read_frames=4"), probeOutput);
        assertTrue(probeOutput.contains("format_name=mov,mp4,"), probeOutput);
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("users/7/workflows/31/merge/film.mp4", bytes, "video/mp4");

        assertNull(composer(storage, new FakeRunner()).findExisting(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, .4, false, 1))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
            "mp41", "mp42", "avc1", "dash", "M4V ", "M4A ", "IsOm"})
    void recoversPlayableMp4WithRecognizedMajorBrand(String brand) throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        String key = "users/7/workflows/31/merge/film.mp4";
        storage.put(key, Files.readAllBytes(validMp4("-brand", brand)), "video/mp4");

        WorkflowFilmComposer.FilmOutput existing = composer(storage, new FakeRunner()).findExisting(
                7L, 31L, "merge", List.of(new Clip("source.mp4", 0, .4, false, 1)));

        assertNotNull(existing);
        assertEquals(key, existing.storageKey());
    }

    private Path validMp4(String... options) throws Exception {
        Path source = directory.resolve("valid.mp4");
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=red:s=32x32:r=10", "-t", "0.4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        command.addAll(List.of(options));
        command.add(source.toString());
        Process fixture = new ProcessBuilder(command).start();
        assertTrue(fixture.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, fixture.exitValue());
        return source;
    }

    @Test
    void buildsTrimVolumeAndConcatFilterInClipOrder() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("b.mp4"), Path.of("a.mp4")),
                List.of(new Clip("b.mp4", 1, 4, false, .5),
                        new Clip("a.mp4", 0, 2, true, 1)),
                Path.of("out.mp4"));

        String command = String.join(" ", args);
        assertTrue(command.contains("trim=start=1.0:end=4.0"));
        assertTrue(command.contains("volume=0.5"));
        assertTrue(command.contains("volume=0"));
        assertTrue(command.contains("concat=n=2:v=1:a=1"));
        // 缺省沿用历史 1280x720
        assertTrue(command.contains("scale=1280:720:force_original_aspect_ratio=decrease"));
        assertTrue(command.contains("pad=1280:720:(ow-iw)/2:(oh-ih)/2"));
    }

    @Test
    void mixesAudioTrackWithAdelayAndAmixWhenAudioClipsPresent() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")),
                List.of(new Clip("v.mp4", 0, 5, false, 1)),
                List.of(true), null, Path.of("out.mp4"),
                List.of(new cn.aigc.dit.server.workflow.WorkflowFilmComposer.AudioClip("bgm.mp3", 2.5, 0, 3, false, 0.8)),
                List.of(Path.of("bgm.mp3")));

        String command = String.join(" ", args);
        // 音轨输入追加在视频输入之后
        assertTrue(command.contains("-i v.mp4 -i bgm.mp3"));
        // atrim 按音轨自身素材时间裁剪，volume 作用其上
        assertTrue(command.contains("atrim=start=0.0:end=3.0"), command);
        assertTrue(command.contains("aformat=channel_layouts=stereo,volume=0.8"));
        // adelay 对齐成片内混入位置：start=2.5s → 2500ms 双声道
        assertTrue(command.contains("adelay=2500|2500"), command);
        // amix 混音：拼接音频 + 1 条音轨 = 2 输入，以视频时长为准
        assertTrue(command.contains("amix=inputs=2:duration=first:dropout_transition=0"), command);
        assertTrue(command.contains("-map [aout]"), command);
        // concat 输出标签从 [v][a] 变为 [v] + [a]（供 amix 引用）
        assertTrue(command.contains("concat=n=1:v=1:a=1[v]"), command);
    }

    @Test
    void skipsAmixEntirelyWhenNoAudioClips() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")),
                List.of(new Clip("v.mp4", 0, 5, false, 1)),
                List.of(true), null, Path.of("out.mp4"));

        String command = String.join(" ", args);
        assertFalse(command.contains("amix"), command);
        assertFalse(command.contains("adelay"), command);
        assertTrue(command.contains("concat=n=1:v=1:a=1[v][a]"), command);
        assertTrue(command.contains("-map [a]"), command);
    }

    @Test
    void mutedAudioTrackGetsZeroVolumeAndZeroDelayKeepsNoAdelay() {
        List<String> args = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("v.mp4")),
                List.of(new Clip("v.mp4", 0, 5, false, 1)),
                List.of(true), null, Path.of("out.mp4"),
                List.of(new cn.aigc.dit.server.workflow.WorkflowFilmComposer.AudioClip("bgm.mp3", 0, 0, 3, true, 1)),
                List.of(Path.of("bgm.mp3")));

        String command = String.join(" ", args);
        assertTrue(command.contains("volume=0"), command);
        // start=0 不需要 adelay
        assertFalse(command.contains("adelay="), command);
    }

    @Test
    void appliesWhitelistedTargetSizeInScaleAndPad() {
        for (var entry : Map.of(
                new WorkflowFilmComposer.FilmSize(1920, 1080), "scale=1920:1080:force_original_aspect_ratio=decrease",
                new WorkflowFilmComposer.FilmSize(1080, 1920), "scale=1080:1920:force_original_aspect_ratio=decrease",
                new WorkflowFilmComposer.FilmSize(1920, 816), "pad=1920:816:(ow-iw)/2:(oh-ih)/2",
                new WorkflowFilmComposer.FilmSize(1280, 544), "pad=1280:544:(ow-iw)/2:(oh-ih)/2").entrySet()) {
            String command = String.join(" ", FfmpegWorkflowFilmComposer.args(
                    List.of(Path.of("a.mp4")), List.of(new Clip("a.mp4", 0, 1, false, 1)),
                    List.of(true), entry.getKey(), Path.of("out.mp4")));
            assertTrue(command.contains(entry.getValue()), entry.getKey() + " missing in " + command);
        }
    }

    @Test
    void rejectsNonWhitelistedTargetSizeBeforeBuildingArgs() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("a.mp4")), List.of(new Clip("a.mp4", 0, 1, false, 1)),
                List.of(true), new WorkflowFilmComposer.FilmSize(1234, 567), Path.of("out.mp4")));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowFilmComposer.FilmSize.of(1234, 567));
        // 画幅/清晰度解析：白名单命中、未知画幅返回 null（走默认）
        assertEquals(new WorkflowFilmComposer.FilmSize(1920, 816),
                WorkflowFilmComposer.FilmSize.of("2.35:1", "1080p"));
        assertEquals(new WorkflowFilmComposer.FilmSize(1280, 720),
                WorkflowFilmComposer.FilmSize.of("16:9", "720"));
        assertNull(WorkflowFilmComposer.FilmSize.of("21:9", "1080p"));
        assertNull(WorkflowFilmComposer.FilmSize.of(null, "1080p"));
    }

    @Test
    void filmOutputCarriesTargetDimensions() {
        WorkflowFilmComposer.FilmOutput output = new WorkflowFilmComposer.FilmOutput(
                "film.mp4", "poster.webp", 3.5, 1920, 816);
        assertEquals(1920, output.width());
        assertEquals(816, output.height());
        // 三参构造（历史默认）不带尺寸
        WorkflowFilmComposer.FilmOutput legacy = new WorkflowFilmComposer.FilmOutput(
                "film.mp4", "poster.webp", 3.5);
        assertNull(legacy.width());
        assertNull(legacy.height());
    }

    @Test
    void rejectsEmptyClipsAndInvalidTrimBeforeWriting() {
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        FakeRunner runner = new FakeRunner();
        FfmpegWorkflowFilmComposer composer = composer(storage, runner);

        assertThrows(BizException.class, () -> composer.compose(7L, 31L, "merge", List.of()));
        assertThrows(BizException.class, () -> composer.compose(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 3, 3, false, 1))));
        assertEquals(0, runner.commands.size());
        assertFalse(Files.exists(directory.resolve("objects/users/7/workflows/31/merge/film.mp4")));
    }

    @Test
    void acceptsVolumeTwoButRejectsAnythingAboveTwo() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("source.mp4", Files.readAllBytes(validMp4("-t", "2")), "video/mp4");
        FakeRunner runner = new FakeRunner();
        FfmpegWorkflowFilmComposer composer = composer(storage, runner);

        composer.compose(7L, 31L, "merge", List.of(new Clip("source.mp4", 0, 2, false, 2)));
        BizException error = assertThrows(BizException.class, () -> composer.compose(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, 2, false, 2.01))));

        assertTrue(error.getMessage().contains("音量无效"));
        assertEquals(1, runner.commands.size());
    }

    @Test
    void storesFilmAtWorkflowKeyAndCleansTemporaryFiles() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        byte[] second = Files.readAllBytes(validMp4("-t", "6"));
        byte[] first = Files.readAllBytes(validMp4("-t", "6", "-vf", "hue=h=90"));
        storage.put("b.mp4", second, "video/mp4");
        storage.put("a.mp4", first, "video/mp4");
        FakeRunner runner = new FakeRunner();
        FfmpegWorkflowFilmComposer composer = composer(storage, runner);

        WorkflowFilmComposer.FilmOutput output = composer.compose(7L, 31L, "merge",
                List.of(new Clip("b.mp4", 1, 4, false, .5), new Clip("a.mp4", 0, 2, true, 1)));

        assertEquals("users/7/workflows/31/merge/film.mp4", output.storageKey());
        assertEquals("users/7/workflows/31/merge/poster.webp", output.posterKey());
        assertEquals(5.0, output.durationSeconds());
        assertEquals("ftyp", new String(storage.getBytes(output.storageKey()), 4, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(second, runner.inputContents.get(0));
        assertArrayEquals(first, runner.inputContents.get(1));
        assertEquals(1, runner.commands.size());
        assertTrue(runner.tempPaths.stream().noneMatch(Files::exists));
    }

    @Test
    void streamsSourceAndFilmThroughObjectStorage() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        StreamingOnlyStorage storage = new StreamingOnlyStorage(directory.resolve("objects"));
        byte[] source = Files.readAllBytes(validMp4("-t", "2"));
        storage.put("source.mp4", new ByteArrayInputStream(source), source.length, "video/mp4");
        FakeRunner runner = new FakeRunner();

        WorkflowFilmComposer.FilmOutput output = composer(storage, runner).compose(7L, 31L, "merge",
                List.of(new Clip("source.mp4", 0, 2, false, 1)));

        try (InputStream film = storage.get(output.storageKey())) {
            assertEquals("ftyp", new String(film.readNBytes(8), 4, 4, StandardCharsets.US_ASCII));
        }
        assertArrayEquals(source, runner.inputContents.get(0));
        assertTrue(runner.tempPaths.stream().noneMatch(Files::exists));
    }

    @Test
    void synthesizesSilenceWhenSourceHasNoAudio() {
        String command = String.join(" ", FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("silent.mp4")), List.of(new Clip("silent.mp4", 2, 5, false, 1)),
                List.of(false), Path.of("out.mp4")));
        assertTrue(command.contains("anullsrc=channel_layout=stereo:sample_rate=48000"));
        assertTrue(command.contains("concat=n=1:v=1:a=1"));
        assertFalse(command.contains("[0:a]"));
    }

    @Test
    void encodeFailureLeavesNoStoredFilmAndCleansTemporaryFiles() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("source.mp4", Files.readAllBytes(validMp4("-t", "2")), "video/mp4");
        FakeRunner runner = new FakeRunner();
        runner.failRun = true;

        BizException error = assertThrows(BizException.class, () -> composer(storage, runner)
                .compose(7L, 31L, "merge", List.of(new Clip("source.mp4", 0, 2, false, 1))));

        assertTrue(error.getMessage().contains("编码失败"));
        assertFalse(Files.exists(directory.resolve("objects/users/7/workflows/31/merge/film.mp4")));
        assertTrue(runner.tempPaths.stream().noneMatch(Files::exists));
    }

    @Test
    void sourceReadFailureLeavesNoStoredFilm() {
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        FakeRunner runner = new FakeRunner();

        BizException error = assertThrows(BizException.class, () -> composer(storage, runner)
                .compose(7L, 31L, "merge", List.of(new Clip("missing.mp4", 0, 2, false, 1))));

        assertTrue(error.getMessage().contains("读取源视频失败"));
        assertFalse(Files.exists(directory.resolve("objects/users/7/workflows/31/merge/film.mp4")));
        assertEquals(0, runner.commands.size());
    }

    @Test
    void composesAndRecoversRealSilentVideoWithFfmpegWhenInstalled() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path source = directory.resolve("silent.mp4");
        Process fixture = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=red:s=32x32:r=10", "-t", "0.4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", source.toString()).start();
        assertTrue(fixture.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, fixture.exitValue());
        LocalObjectStorage storage = new LocalObjectStorage(directory.resolve("objects"));
        storage.put("silent.mp4", Files.readAllBytes(source), "video/mp4");
        FfmpegWorkflowFilmComposer composer = new FfmpegWorkflowFilmComposer(storage,
                key -> "users/7/workflows/31/merge/poster.webp", new GenerateProperties());

        WorkflowFilmComposer.FilmOutput output = composer.compose(7L, 31L, "merge",
                List.of(new Clip("silent.mp4", 0, .4, false, 1)));

        assertEquals(.4, output.durationSeconds());
        assertTrue(storage.size(output.storageKey()) > 0);
        assertEquals("isom", new String(storage.getBytes(output.storageKey()), 8, 4,
                StandardCharsets.US_ASCII));
        assertEquals(output, composer.findExisting(7L, 31L, "merge",
                List.of(new Clip("silent.mp4", 0, .4, false, 1))));
    }

    @Test
    void systemRunnerStopsSlowFfmpegAtTimeout() {
        Assumptions.assumeTrue(FfmpegSupport.available());
        long started = System.nanoTime();

        BizException error = assertThrows(BizException.class, () -> FfmpegWorkflowFilmComposer.execute(
                List.of("-re", "-f", "lavfi", "-i", "color=c=red:s=16x16:r=1",
                        "-t", "10", "-f", "null", "-"), 1));

        assertTrue(error.getMessage().contains("超时"));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
    }

    @Test
    void interruptedProcessIsKilledReapedAndInterruptFlagRestored() {
        InterruptingProcess process = new InterruptingProcess();
        try {
            BizException error = assertThrows(BizException.class,
                    () -> FfmpegWorkflowFilmComposer.awaitProcess(process, 30));
            assertTrue(error.getMessage().contains("中断"));
            assertTrue(process.destroyed);
            assertTrue(process.forced);
            assertTrue(process.reaped);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // ── V2 画中画（副视频轨）成片烧录 ────────────────────────────────────────

    /** 一次构造出「音轨 amix + 文字叠加 + 画中画」的最复杂组合，供下列用例复用。 */
    private static List<String> pipArgs(Path main, Path bgm, Path png, Path pip, Path out, double opacity) {
        return FfmpegWorkflowFilmComposer.args(
                List.of(main), List.of(new Clip("main.mp4", 0, 4, false, 1)), List.of(true),
                WorkflowFilmComposer.FilmSize.of("16:9", "720p"), out,
                List.of(new WorkflowFilmComposer.AudioClip("bgm.wav", 0, 0, 2, false, 1)),
                List.of(bgm), null, null,
                List.of(new WorkflowFilmComposer.OverlayClip(0, 2, "text", "hi", "#ffffff",
                        24, 400, "center", null, 50, 50, 100, 100)),
                List.of(png),
                List.of(new WorkflowFilmComposer.PipClip(1, 3, "pip.mp4", 0, 2, 70, 25, 30, opacity, true)),
                List.of(pip));
    }

    @Test
    void pipArgsAppendInputAfterOverlaysAndSeparateAmixFromPipChain() {
        List<String> args = pipArgs(Path.of("main.mp4"), Path.of("bgm.wav"), Path.of("overlay.png"),
                Path.of("pip.mp4"), Path.of("out.mp4"), 60);
        String command = String.join(" ", args);
        // 输入顺序：主片 → 音轨 → 叠加 PNG（-loop 1 单帧循环）→ 画中画；索引 3 正是 PiP 输入
        assertTrue(command.contains("-i main.mp4 -i bgm.wav -loop 1 -i overlay.png -i pip.mp4"));
        String filter = args.get(args.indexOf("-filter_complex") + 1);
        assertTrue(filter.contains("[3:v]trim=start=0.000:end=2.000"));
        // 半透明必须保留 alpha，否则 yuva420p 之前的 format 会把 colorchannelmixer 的成果丢掉
        assertTrue(filter.contains("colorchannelmixer=aa=0.60"));
        assertTrue(filter.contains("format=yuva420p[pipv0]"));
        assertTrue(filter.contains("overlay=format=auto:x="));
        assertTrue(filter.contains("enable='between(t,1.000,3.000)'[pip0]"));
        // amix / overlay 链结尾没有分号时，PiP 分支链前必须补上，否则拼成一条非法链
        assertTrue(filter.contains("[aout]; [v][2:v]overlay"));
        assertTrue(filter.contains("; [3:v]trim=start=0.000"));
        // 最终视频 map 指向最后一级 PiP 输出，音频仍走 amix
        assertEquals("[pip0]", args.get(args.indexOf("-map") + 1));
        assertEquals("[aout]", args.get(args.lastIndexOf("-map") + 1));
    }

    @Test
    void opaquePipUsesPlainYuv420pAndEmptyPipKeepsLegacyMap() {
        List<String> opaque = pipArgs(Path.of("main.mp4"), Path.of("bgm.wav"), Path.of("overlay.png"),
                Path.of("pip.mp4"), Path.of("out.mp4"), 100);
        String filter = opaque.get(opaque.indexOf("-filter_complex") + 1);
        assertFalse(filter.contains("colorchannelmixer"));
        assertTrue(filter.contains("scale=384:-2,format=yuv420p[pipv0]"));

        List<String> legacy = FfmpegWorkflowFilmComposer.args(
                List.of(Path.of("main.mp4")), List.of(new Clip("main.mp4", 0, 4, false, 1)),
                List.of(true), WorkflowFilmComposer.FilmSize.of("16:9", "720p"), Path.of("out.mp4"),
                List.of(), List.of(), null, null, List.of(), List.of(), List.of(), List.of());
        String legacyFilter = legacy.get(legacy.indexOf("-filter_complex") + 1);
        assertFalse(legacyFilter.contains("pip"));
        assertEquals("[v]", legacy.get(legacy.indexOf("-map") + 1));
    }

    @Test
    void pipIsActuallyBurnedIntoFilmWhenFfmpegInstalled() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path main = directory.resolve("main.mp4");
        Path pip = directory.resolve("pip.mp4");
        Path bgm = directory.resolve("bgm.wav");
        Path png = directory.resolve("overlay.png");
        Path out = directory.resolve("out.mp4");
        ffmpegFixture("color=c=red:s=64x36:r=30", 4, main);
        ffmpegFixture("color=c=blue:s=64x36:r=30", 3, pip);
        ffmpeg("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
                "-t", "2", "-c:a", "pcm_s16le", bgm.toString());
        java.awt.image.BufferedImage transparent =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        assertTrue(javax.imageio.ImageIO.write(transparent, "png", png.toFile()));

        FfmpegSupport.run(pipArgs(main, bgm, png, pip, out, 100), 90);

        assertTrue(Files.size(out) > 0);
        Double duration = FfmpegSupport.durationSeconds(out);
        assertNotNull(duration);
        assertTrue(duration >= 3.5, "成片时长异常：" + duration);
        // 抽 t=2s 的一帧：主画面纯红、画中画纯蓝，必须能数出成片的蓝色像素
        Path frame = directory.resolve("frame.rgb");
        ffmpeg("-ss", "2", "-i", out.toString(), "-frames:v", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", frame.toString());
        byte[] pixels = Files.readAllBytes(frame);
        int blue = 0;
        for (int i = 0; i + 2 < pixels.length; i += 3) {
            int r = pixels[i] & 0xff, g = pixels[i + 1] & 0xff, b = pixels[i + 2] & 0xff;
            if (b > r + 60 && b > g + 60) blue++;
        }
        assertTrue(blue > 100, "画中画没有出现在成片里，蓝色像素数=" + blue);
    }

    /**
     * 字幕/图形叠加真实落盘回归（ffmpeg 9.0.2 实测）：
     * ① PNG 输入缺 -loop 1 → enable 窗口起点 > 0 时单帧 PNG 已 EOF，窗口内无帧可叠，字幕静默丢失；
     * ② 加了 -loop 1 却没给每级 overlay 加 shortest=1 → 无限 PNG 流让 ffmpeg 永不结束（超时）。
     * 这里直接跑真实命令：断言成片时长严格等于主片，并抽查窗口内/外的白色像素。
     */
    @Test
    void pngOverlaysAreBurnedInsideEnableWindowsAndFilmTerminates() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path main = directory.resolve("overlay-main.mp4");
        Path pngA = directory.resolve("overlay-a.png");
        Path pngB = directory.resolve("overlay-b.png");
        Path out = directory.resolve("overlay-out.mp4");
        ffmpegFixture("color=c=red:s=64x36:r=30", 4, main);

        int width = 1280, height = 720;
        for (Path png : List.of(pngA, pngB)) {
            Files.write(png, OverlayRenderer.renderPng(List.of(new OverlayRenderer.Overlay(
                    "text", 0, 1, "字幕", "#ffffff", 32, 700, "center",
                    null, 0, 0, 0, 100)), width, height));
        }
        List<WorkflowFilmComposer.OverlayClip> overlays = List.of(
                new WorkflowFilmComposer.OverlayClip(0.5, 1.5, "text", "字幕", "#ffffff",
                        32, 700, "center", null, 0, 0, 0, 100),
                new WorkflowFilmComposer.OverlayClip(2.5, 3.5, "text", "字幕", "#ffffff",
                        32, 700, "center", null, 0, 0, 0, 100));
        FfmpegSupport.run(FfmpegWorkflowFilmComposer.args(
                List.of(main), List.of(new Clip("overlay-main.mp4", 0, 4, false, 1)), List.of(true),
                WorkflowFilmComposer.FilmSize.of("16:9", "720p"), out,
                List.of(), List.of(), null, null, overlays,
                List.of(pngA, pngB), List.of(), List.of()), 90);

        Double duration = FfmpegSupport.durationSeconds(out);
        assertNotNull(duration);
        assertTrue(duration >= 3.8 && duration <= 4.2,
                "成片必须随主片结束，不能因无限 PNG 流拖尾/超时：" + duration);
        assertTrue(whitePixels(out, 1.0) > 500, "第一段窗口内应烧上白色字幕");
        assertTrue(whitePixels(out, 3.0) > 500, "第二段窗口内应烧上白色字幕");
        assertEquals(0, whitePixels(out, 2.0), "两段之间不应有叠加");
        assertEquals(0, whitePixels(out, 3.8), "窗口结束后不应有叠加");
    }

    private int whitePixels(Path film, double atSecond) throws Exception {
        Path frame = directory.resolve("frame-" + atSecond + ".rgb");
        ffmpeg("-ss", Double.toString(atSecond), "-i", film.toString(), "-frames:v", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", frame.toString());
        byte[] pixels = Files.readAllBytes(frame);
        int white = 0;
        for (int i = 0; i + 2 < pixels.length; i += 3) {
            int r = pixels[i] & 0xff, g = pixels[i + 1] & 0xff, b = pixels[i + 2] & 0xff;
            if (r > 200 && g > 200 && b > 200) white++;
        }
        return white;
    }

    private void ffmpegFixture(String color, int seconds, Path target) throws Exception {
        ffmpeg("-f", "lavfi", "-i", color,
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
                "-t", Integer.toString(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", target.toString());
    }

    private void ffmpeg(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                FfmpegSupport.ffmpegBinary(), "-y", "-hide_banner", "-loglevel", "error"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), log);
        assertEquals(0, process.exitValue(), log);
    }

    private FfmpegWorkflowFilmComposer composer(LocalObjectStorage storage, FakeRunner runner) {
        return new FfmpegWorkflowFilmComposer(storage,
                key -> "users/7/workflows/31/merge/poster.webp", new GenerateProperties(), runner);
    }

    private static final class StreamingOnlyStorage extends LocalObjectStorage {
        private StreamingOnlyStorage(Path root) {
            super(root);
        }

        @Override
        public byte[] getBytes(String storageKey) {
            throw new AssertionError("composer must stream source objects");
        }

        @Override
        public void put(String storageKey, byte[] content, String contentType) {
            throw new AssertionError("composer must stream encoded films");
        }
    }

    private static final class InterruptingProcess extends Process {
        private boolean destroyed;
        private boolean forced;
        private boolean reaped;
        private int waits;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            reaped = true;
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (++waits == 1) {
                throw new InterruptedException("cancelled");
            }
            return false;
        }

        @Override
        public int exitValue() {
            if (!reaped) {
                throw new IllegalThreadStateException();
            }
            return 137;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            forced = true;
            return this;
        }
    }

    private static final class FakeRunner implements FfmpegWorkflowFilmComposer.Runner {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Path> tempPaths = new ArrayList<>();
        private final List<byte[]> inputContents = new ArrayList<>();
        private boolean failRun;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean hasAudio(Path input) throws Exception {
            tempPaths.add(input);
            inputContents.add(Files.readAllBytes(input));
            return false;
        }

        @Override
        public void run(List<String> args, long timeoutSeconds) throws Exception {
            commands.add(args);
            Path output = Path.of(args.get(args.size() - 1));
            tempPaths.add(output);
            if (failRun) {
                throw new IllegalStateException("encoder failed");
            }
            FfmpegWorkflowFilmComposer.execute(args, timeoutSeconds);
        }
    }
}
