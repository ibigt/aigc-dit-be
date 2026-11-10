package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 一键分析占用锁。必须独立提交，否则长耗时 LLM 结束前刷新页面看不到「分析中」。
 *
 * <p>锁有两个时间戳：
 * <ul>
 *   <li>{@code script_analyzing_at}：分析开始时间，前端用它算「已等待多久」，全程不改；</li>
 *   <li>{@code script_analyzing_heartbeat_at}：心跳，分析期间每分钟续期一次，
 *       「是否还在分析」看它。</li>
 * </ul>
 *
 * <p>为什么要有心跳：慢模型（MiniMax 等）跑一集要十几分钟，而前端「分析中」只认锁，
 * 锁过期（15 分钟）后页面进度会自己清空、用户还能再点一次分析，两次分析写同一集。
 * 心跳让「还在跑」和「跑完了」区分开：进程活着就一直续期，进程被杀掉就自然过期。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DramaScriptAnalyzeLock {

    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    /** 心跳续期间隔。过期窗口 15 分钟，1 分钟续一次足够安全。 */
    static final long HEARTBEAT_INTERVAL_SECONDS = 60L;
    static final String BUSY_MESSAGE = "正在分析剧本，请稍候";

    private final DramaEpisodeMapper episodeMapper;

    /** 心跳调度线程。守护线程 + @PreDestroy 关闭，不挡 JVM 退出。 */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "drama-analyze-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatScheduler.shutdownNow();
    }

    public boolean isActive(DramaEpisode episode) {
        return isActiveAt(episode, OffsetDateTime.now());
    }

    /** 锁是否仍然有效：看心跳，没有心跳就退回开始时间（老数据 / 兼容旧行）。 */
    static boolean isActiveAt(DramaEpisode episode, OffsetDateTime now) {
        OffsetDateTime mark = activeMark(episode);
        return mark != null && mark.isAfter(now.minus(STALE_AFTER));
    }

    /** 判定「还在分析」用的时间戳：优先心跳，其次开始时间。 */
    static OffsetDateTime activeMark(DramaEpisode episode) {
        if (episode == null) {
            return null;
        }
        if (episode.getScriptAnalyzingHeartbeatAt() != null) {
            return episode.getScriptAnalyzingHeartbeatAt();
        }
        return episode.getScriptAnalyzingAt();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(Long episodeId) {
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (isActive(episode)
                || episodeMapper.claimScriptAnalysis(episodeId, now, now.minus(STALE_AFTER)) != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, BUSY_MESSAGE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long episodeId) {
        episodeMapper.releaseScriptAnalysis(episodeId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long episodeId, String message) {
        episodeMapper.failScriptAnalysis(episodeId, DramaContract.clipError(message));
    }

    /**
     * 心跳句柄：停止续期。
     */
    @FunctionalInterface
    public interface Heartbeat {
        void stop();
    }

    /**
     * 开始续期心跳，返回的句柄停止时不再续期（分析结束 / 失败都要停）。
     *
     * <p>分析开始到结束之间调用一次即可：锁过期判定看心跳，心跳断了自然过期。
     */
    public Heartbeat startHeartbeat(Long episodeId) {
        return startHeartbeat(episodeId, HEARTBEAT_INTERVAL_SECONDS);
    }

    /**
     * 开始续期心跳（可指定续期间隔，测试用）。
     */
    Heartbeat startHeartbeat(Long episodeId, long intervalSeconds) {
        if (episodeId == null) {
            return () -> {
            };
        }
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                refreshHeartbeat(episodeId);
            } catch (RuntimeException e) {
                log.warn("[drama-analyze] 心跳续期失败 episodeId={} error={}", episodeId, e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        return () -> future.cancel(false);
    }

    /** 续期一次心跳（对外入口，走事务代理）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(Long episodeId) {
        refreshHeartbeat(episodeId);
    }

    /**
     * 心跳续期的真正动作。
     *
     * <p>调度线程是自调用 {@link #heartbeat(Long)}，拿不到事务代理；这里直接用 mapper
     * 发一条 UPDATE —— 单条语句自带提交，不需要事务。
     */
    private void refreshHeartbeat(Long episodeId) {
        episodeMapper.refreshScriptAnalysisHeartbeat(episodeId, OffsetDateTime.now());
    }
}
