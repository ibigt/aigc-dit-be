package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DramaScriptAnalyzeLockTest {

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @InjectMocks
    private DramaScriptAnalyzeLock lock;

    @Test
    void isActiveIgnoresStaleTimestamp() {
        DramaEpisode episode = new DramaEpisode();
        episode.setScriptAnalyzingAt(OffsetDateTime.now().minusMinutes(16));
        assertFalse(lock.isActive(episode));
    }

    @Test
    void claimRejectsWhenAlreadyAnalyzing() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setScriptAnalyzingAt(OffsetDateTime.now());
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        BizException error = assertThrows(BizException.class, () -> lock.claim(31L));
        assertEquals(DramaScriptAnalyzeLock.BUSY_MESSAGE, error.getMessage());
    }

    @Test
    void claimWritesTimestamp() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(episodeMapper.claimScriptAnalysis(org.mockito.ArgumentMatchers.eq(31L), any(), any())).thenReturn(1);

        lock.claim(31L);

        verify(episodeMapper).claimScriptAnalysis(org.mockito.ArgumentMatchers.eq(31L), any(), any());
    }
}
