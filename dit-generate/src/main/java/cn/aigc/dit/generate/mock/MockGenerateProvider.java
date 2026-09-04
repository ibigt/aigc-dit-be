package cn.aigc.dit.generate.mock;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock Adapter：吃 Canonical {@link GenerateRequest}，把成片写入对象存储后返回 {@link PollResult}。
 * 不翻译任何厂商 HTTP 字段。
 */
@Slf4j
@Component
public class MockGenerateProvider implements GenerateProvider {

    public static final String KEY = "mock";

    private final ObjectStorage objectStorage;
    private final GenerateProperties properties;
    private final Map<String, MockJob> jobs = new ConcurrentHashMap<>();

    public MockGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    @Override
    public String providerKey() {
        return KEY;
    }

    @Override
    public SubmitResult submit(GenerateRequest request) {
        String jobId = "mock-" + UUID.randomUUID();
        jobs.put(jobId, new MockJob(request, System.currentTimeMillis()));
        log.info("Mock submit jobId={}, taskId={}, mode={}, copies={}",
                jobId, request.getTaskId(), request.getMode(), request.resolvedCopies());
        return SubmitResult.accepted(jobId);
    }

    @Override
    public PollResult poll(String jobId) {
        MockJob job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "Mock 任务不存在");
        }
        long elapsed = System.currentTimeMillis() - job.submittedAt;
        int delay = properties.getMock().getDelayMs();
        if (elapsed < delay) {
            int progress = delay <= 0 ? 99 : Math.min(95, (int) (elapsed * 90 / delay));
            return PollResult.running(jobId, Math.max(5, progress));
        }
        if (job.outputKeys == null) {
            job.outputKeys = materialize(job.request);
        }
        return PollResult.succeeded(jobId, job.outputKeys);
    }

    @Override
    public void cancel(String jobId) {
        jobs.remove(jobId);
        log.info("Mock cancel jobId={}", jobId);
    }

    private List<String> materialize(GenerateRequest request) {
        int copies = request.resolvedCopies();
        List<String> keys = new ArrayList<>(copies);
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        for (int i = 1; i <= copies; i++) {
            MockMediaFactory.RenderedObject rendered = MockMediaFactory.render(request, i);
            String key = "users/" + userId + "/outputs/" + taskId + "/" + i + rendered.extension();
            objectStorage.put(key, rendered.bytes(), rendered.contentType());
            if (rendered.posterBytes() != null && rendered.posterBytes().length > 0) {
                int dot = key.lastIndexOf('.');
                String posterKey = (dot > 0 ? key.substring(0, dot) : key) + "-poster.webp";
                objectStorage.put(posterKey, rendered.posterBytes(), "image/webp");
            }
            keys.add(key);
        }
        return keys;
    }

    private static final class MockJob {
        private final GenerateRequest request;
        private final long submittedAt;
        private volatile List<String> outputKeys;

        private MockJob(GenerateRequest request, long submittedAt) {
            this.request = request;
            this.submittedAt = submittedAt;
        }
    }
}
