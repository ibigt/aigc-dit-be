package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 引擎判定的并发回归。历史失败模式：单例 Bean 用实例字段记 lastEngine，
 * 两个项目并发导入会互串 engine，且该值落在 drama_episode.engine（带 CHECK 约束）事后难洗。
 */
@ExtendWith(MockitoExtension.class)
class DramaTextConcurrencyTest {

    private static final long SERIES_A = 1L;
    private static final long SERIES_B = 2L;
    private static final int THREADS = 8;
    private static final int ROUNDS = 20;
    private static final String RAW = "第一场 幻梦侦探社 日 内\n洛白：开始了。";
    @Mock
    private TextChatGateway gateway;

    @Test
    void concurrentImportsDoNotCrossContaminateEngine() throws Exception {
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);
        lenient().when(gateway.resolve(anyLong())).thenReturn(Optional.of(
                new ResolvedTextModel(5L, "Agnes 对话", "agnes-text", "官方", "https://x", "agnes-2.5-flash")));
        when(gateway.chat(anyLong(), any())).thenAnswer(call -> {
            Thread.sleep(2);
            long seriesId = call.getArgument(0);
            String location = seriesId == SERIES_A ? "模型场景A" : "模型场景B";
            String json = "{\"title\":\"模型标题\",\"scenes\":[{\"index\":1,\"heading\":\""
                    + location + " 日 内\",\"characters\":[\"洛白\"],\"location\":\""
                    + location + "\",\"dialogue\":[]}]}";
            return TextChatResult.of(json, "m", "stop");
        });

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger checked = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                jobs.add(pool.submit(() -> {
                    awaitQuietly(start, failure);
                    for (int round = 0; round < ROUNDS && failure.get() == null; round++) {
                        // 同一线程交替解析两个系列：任何按线程残留的引擎判定都会在这里露馅。
                        for (long seriesId : new long[]{SERIES_A, SERIES_B, SERIES_B, SERIES_A}) {
                            String want = DramaTextService.ENGINE_LLM;
                            String location = seriesId == SERIES_A ? "模型场景A" : "模型场景B";
                            ScriptDoc doc = service.parseScript(seriesId, RAW);
                            String got = service.engine(seriesId);
                            checked.incrementAndGet();
                            if (!want.equals(got)) {
                                failure.compareAndSet(null, "series " + seriesId + " 期望 " + want + " 实得 " + got);
                            } else if (!location.equals(doc.getScenes().get(0).getLocation())) {
                                failure.compareAndSet(null, "series " + seriesId + " 的 script 与 engine 不同源："
                                        + doc.getScenes().get(0).getLocation());
                            }
                        }
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> job : jobs) {
                job.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(THREADS * ROUNDS * 4, checked.get());
        assertTrue(failure.get() == null, failure.get());
    }

    private static void awaitQuietly(CountDownLatch latch, AtomicReference<String> failure) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, "启动闸被中断");
        }
    }
}
