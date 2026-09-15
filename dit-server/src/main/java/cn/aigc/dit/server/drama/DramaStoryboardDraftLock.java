package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 拆镜占用锁。必须独立提交，否则长耗时 LLM 事务未结束前刷新页面看不到「拆镜中」。
 */
@Service
@RequiredArgsConstructor
public class DramaStoryboardDraftLock {

    static final Duration STALE_AFTER = Duration.ofMinutes(10);
    static final String BUSY_MESSAGE = "正在拆镜，请稍候";

    private final DramaEpisodeMapper episodeMapper;

    public boolean isActive(DramaEpisode episode) {
        if (episode == null || episode.getStoryboardDraftingAt() == null) {
            return false;
        }
        return episode.getStoryboardDraftingAt().isAfter(OffsetDateTime.now().minus(STALE_AFTER));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(Long episodeId) {
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (isActive(episode)
                || episodeMapper.claimStoryboardDraft(episodeId, now, now.minus(STALE_AFTER)) != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, BUSY_MESSAGE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long episodeId) {
        episodeMapper.releaseStoryboardDraft(episodeId);
    }
}
