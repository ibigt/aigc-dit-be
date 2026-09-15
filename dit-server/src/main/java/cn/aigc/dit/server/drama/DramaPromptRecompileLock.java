package cn.aigc.dit.server.drama;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 切换生成方式后的后台重编译占用。进程内即可：前端轮询 storyboard.promptRecompiling。
 */
@Component
public class DramaPromptRecompileLock {

    static final String BUSY_MESSAGE = "正在按新方式重新编译提示词，请稍候";

    private final ConcurrentHashMap<Long, AtomicInteger> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> busy = new ConcurrentHashMap<>();

    public int begin(Long seriesId) {
        if (seriesId == null) {
            return 0;
        }
        busy.put(seriesId, Boolean.TRUE);
        return epochs.computeIfAbsent(seriesId, key -> new AtomicInteger()).incrementAndGet();
    }

    public boolean isActive(Long seriesId) {
        return seriesId != null && Boolean.TRUE.equals(busy.get(seriesId));
    }

    public void end(Long seriesId, int epoch) {
        if (seriesId == null) {
            return;
        }
        AtomicInteger current = epochs.get(seriesId);
        if (current != null && current.get() == epoch) {
            busy.remove(seriesId);
        }
    }
}
