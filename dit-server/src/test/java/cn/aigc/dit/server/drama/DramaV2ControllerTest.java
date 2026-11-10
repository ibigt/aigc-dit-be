package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.agent.DramaAnalysisResult;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.model.req.DramaScriptAnalyzeReq;
import cn.aigc.dit.server.drama.model.resp.DramaScriptAnalyzeV2Resp;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DramaV2Controller 测试。
 */
class DramaV2ControllerTest {

    private final DramaAnalysisFacade facade = mock(DramaAnalysisFacade.class);
    private final DramaEpisodeMapper episodeMapper = mock(DramaEpisodeMapper.class);
    private final DramaAssetMapper assetMapper = mock(DramaAssetMapper.class);
    private final DramaStoryboardService storyboardService = mock(DramaStoryboardService.class);
    private final DramaService dramaService = mock(DramaService.class);

    private final DramaV2Controller controller = new DramaV2Controller(
            facade, episodeMapper, assetMapper, storyboardService, dramaService);

    @BeforeEach
    void setUp() {
        UserContext.set(new LoginUser(7L, "tester", "t@x", "Tester", "USER"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private DramaEpisode episode(long id, long userId) {
        DramaEpisode ep = new DramaEpisode();
        ep.setId(id);
        ep.setSeriesId(1L);
        ep.setUserId(userId);
        ep.setSourceText("原始文本");
        return ep;
    }

    @Test
    void analyzeV2_success_returnsFullResp() {
        when(episodeMapper.selectById(100L)).thenReturn(episode(100L, 7L));
        DramaAnalysisResult result = DramaAnalysisResult.builder()
                .skeleton(new SkeletonDoc("", "", List.of(), List.of(), List.of()))
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .addTokens(100, 200)
                .durationMs(500)
                .build();
        when(facade.analyzeV2(any(), any(), any(), any())).thenReturn(result);
        when(facade.buildWarnings(result)).thenReturn(List.of("v2 ok"));

        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        DramaScriptAnalyzeV2Resp resp = controller.analyzeV2(100L, req);

        assertEquals(DramaAnalysisFacade.ENGINE_V2, resp.getEngine());
        assertNull(resp.getFailedAt());
        assertEquals(100L, resp.getPromptTokens());
        assertEquals(200L, resp.getCompletionTokens());
        assertEquals(500L, resp.getDurationMs());
        assertEquals(List.of("v2 ok"), resp.getWarnings());
    }

    @Test
    void analyzeV2_failure_throwsBizException() {
        when(episodeMapper.selectById(100L)).thenReturn(episode(100L, 7L));
        when(facade.analyzeV2(any(), any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL, "脚本解析失败"));

        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        BizException ex = assertThrows(BizException.class,
                () -> controller.analyzeV2(100L, req));
        assertTrue(ex.getMessage().contains("脚本解析失败"));
    }

    @Test
    void analyzeV2_wrongUser_throwsForbidden() {
        when(episodeMapper.selectById(100L)).thenReturn(episode(100L, 999L));  // 不同 user
        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        BizException ex = assertThrows(BizException.class,
                () -> controller.analyzeV2(100L, req));
        assertEquals(ErrorCode.FORBIDDEN, ex.getCode());
    }

    @Test
    void analyzeV2_episodeNotFound_throwsNotFound() {
        when(episodeMapper.selectById(100L)).thenReturn(null);
        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        BizException ex = assertThrows(BizException.class,
                () -> controller.analyzeV2(100L, req));
        assertEquals(ErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void regenerate_noExistingScript_throwsBadRequest() {
        when(episodeMapper.selectById(100L)).thenReturn(episode(100L, 7L));
        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        BizException ex = assertThrows(BizException.class,
                () -> controller.regenerate(100L, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("还没有解析出剧本"), "实际错误：" + ex.getMessage());
    }

    @Test
    void regenerate_withScript_runsDownstream() {
        DramaEpisode ep = episode(100L, 7L);
        Map<String, Object> scriptMap = new HashMap<>();
        scriptMap.put("scenes", List.of());
        ep.setScript(scriptMap);
        when(episodeMapper.selectById(100L)).thenReturn(ep);

        DramaAnalysisResult result = DramaAnalysisResult.builder()
                .script(new ScriptDoc())
                .assets(new AssetDraftList())
                .shots(new ShotDraftList())
                .build();
        when(facade.regenerateDownstream(any(), any(), any(), any())).thenReturn(result);
        when(facade.buildWarnings(result)).thenReturn(List.of());

        DramaScriptAnalyzeReq req = new DramaScriptAnalyzeReq();
        req.setSourceText("raw");
        DramaScriptAnalyzeV2Resp resp = controller.regenerate(100L, req);

        assertEquals(DramaAnalysisFacade.ENGINE_V2, resp.getEngine());
    }
}
