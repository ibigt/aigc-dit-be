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
