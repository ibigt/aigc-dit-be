package cn.aigc.dit.generate.mock;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockGenerateProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void submitAndPollWritesCopiesToStorage() throws Exception {
        GenerateProperties properties = new GenerateProperties();
        properties.getMock().setDelayMs(0);
        MockGenerateProvider provider = new MockGenerateProvider(new LocalObjectStorage(tempDir), properties);
        GenerateRequest request = new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("image")
                .setMode("i2i")
                .setPrompt("改成暖棕色绒面")
                .setRatio("16:9")
                .setResolution("2K")
                .setCopies(2);

        SubmitResult submitted = provider.submit(request);
        PollResult result = provider.poll(submitted.getJobId());

        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(2, result.getOutputKeys().size());
        assertNotNull(result.getOutputStorageKey());
        assertTrue(result.getOutputKeys().get(0).contains("users/8/outputs/42/"));
        assertTrue(result.getOutputKeys().get(0).endsWith(".webp"));
        assertTrue(Files.size(tempDir.resolve(result.getOutputKeys().get(0))) > 1000);
    }

    @Test
    void videoPollWritesPrototypeWebm() throws Exception {
        GenerateProperties properties = new GenerateProperties();
        properties.getMock().setDelayMs(0);
        MockGenerateProvider provider = new MockGenerateProvider(new LocalObjectStorage(tempDir), properties);
        GenerateRequest request = new GenerateRequest()
                .setUserId(8L)
                .setTaskId(43L)
                .setCategory("video")
                .setMode("t2v")
                .setPrompt("机甲觉醒")
                .setRatio("16:9")
                .setResolution("1080P")
                .setDurationSec(5)
                .setCopies(1);

        PollResult result = provider.poll(provider.submit(request).getJobId());

        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".webm"));
        assertTrue(Files.exists(tempDir.resolve("users/8/outputs/43/1-poster.webp")));
    }

    @Test
    void audioPollWritesPlayableWav() throws Exception {
        GenerateProperties properties = new GenerateProperties();
        properties.getMock().setDelayMs(0);
        MockGenerateProvider provider = new MockGenerateProvider(new LocalObjectStorage(tempDir), properties);
        GenerateRequest request = new GenerateRequest()
                .setUserId(9L)
                .setTaskId(44L)
                .setCategory("audio")
                .setMode("t2a")
                .setPrompt("欢迎使用语音合成")
                .setCopies(1);

        PollResult result = provider.poll(provider.submit(request).getJobId());
        Path output = tempDir.resolve(result.getOutputKeys().get(0));

        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(output.toString().endsWith(".wav"));
        assertEquals("RIFF", new String(Files.readAllBytes(output), 0, 4, StandardCharsets.US_ASCII));
        assertTrue(Files.size(output) > 20_000);
    }

    @Test
    void delayedJobCanBeObservedAndCancelled() {
        GenerateProperties properties = new GenerateProperties();
        properties.getMock().setDelayMs(10_000);
        MockGenerateProvider provider = new MockGenerateProvider(new LocalObjectStorage(tempDir), properties);
        GenerateRequest request = new GenerateRequest().setUserId(1L).setTaskId(45L).setCategory("image");

        SubmitResult submitted = provider.submit(request);
        assertEquals(PollResult.RUNNING, provider.poll(submitted.getJobId()).getStatus());
        provider.cancel(submitted.getJobId());
        PollResult cancelled = provider.poll(submitted.getJobId());

        assertEquals(PollResult.FAILED, cancelled.getStatus());
        assertEquals("Mock 任务不存在", cancelled.getErrorMessage());
    }
}
