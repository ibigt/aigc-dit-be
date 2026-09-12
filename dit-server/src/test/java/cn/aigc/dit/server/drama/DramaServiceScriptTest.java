package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaScriptImportReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.mapper.AiModelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaServiceScriptTest {

    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaAssetMapper assetMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private ObjectProvider<DramaTextService> textService;
    @Mock
    private TextChatGateway textChatGateway;
    @Mock
    private DramaTextService dramaTextService;
    @Mock
    private DramaStoryboardService storyboardService;
    @Mock
    private DramaFilmService filmService;

    private DramaService service;

    @BeforeEach
    void setUp() {
        service = new DramaService(seriesMapper, episodeMapper, assetMapper, aiModelMapper, textService, textChatGateway, storyboardService, filmService);
        org.mockito.Mockito.lenient().when(filmService.preview(any())).thenReturn(new cn.aigc.dit.server.drama.model.resp.DramaFilmResp());
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        org.mockito.Mockito.lenient().when(textService.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient().when(textChatGateway.llmAvailable(anyLong())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void importWithoutTextServiceStoresSourceOnly() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaScriptImportReq req = new DramaScriptImportReq();
        req.setSourceText("  第一场 幻梦侦探社\n洛白：别睡了。  ");
        DramaEpisodeResp resp = service.importSource(31L, req);

        assertEquals("第一场 幻梦侦探社\n洛白：别睡了。", episode.getSourceText());
        assertNull(episode.getScript());
        assertEquals(DramaContract.ENGINE_PENDING, episode.getEngine());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, episode.getStatus());
        assertEquals(DramaContract.ENGINE_PENDING, resp.getEngine());
        assertFalse(resp.getTextServiceAvailable());
        assertFalse(resp.getCanGenerate());
        verify(episodeMapper).updateById(episode);
    }

    @Test
    void importWithTextServiceWritesScript() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(textService.getIfAvailable()).thenReturn(dramaTextService);
        when(dramaTextService.engine(21L)).thenReturn(DramaTextService.ENGINE_RULES);
        when(dramaTextService.parseScript(21L, "第1场 日 内")).thenReturn(sampleDoc());

        DramaScriptImportReq req = new DramaScriptImportReq();
        req.setSourceText("第1场 日 内");
        DramaEpisodeResp resp = service.importSource(31L, req);

        assertEquals(DramaContract.ENGINE_RULES, episode.getEngine());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, episode.getStatus());
        assertEquals("幻梦侦探社", episode.getScript().get("title"));
        assertTrue(resp.getTextServiceAvailable());
        @SuppressWarnings("unchecked")
        List<Object> scenes = (List<Object>) episode.getScript().get("scenes");
        assertEquals(1, scenes.size());
    }

    @Test
    void generateWithoutTextServiceRejected() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        DramaScriptImportReq req = new DramaScriptImportReq();
        req.setSourceText("一句话梗概");
        BizException error = assertThrows(BizException.class, () -> service.generateScript(31L, req));
        assertEquals("剧本生成服务尚未就绪", error.getMessage());
    }

    @Test
    void generateWithoutLlmRejected() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(textService.getIfAvailable()).thenReturn(dramaTextService);
        when(textChatGateway.llmAvailable(21L)).thenReturn(false);
        DramaScriptImportReq req = new DramaScriptImportReq();
        req.setSourceText("一句话梗概");
        BizException error = assertThrows(BizException.class, () -> service.generateScript(31L, req));
        assertEquals("当前项目未绑定可用文本模型，无法生成剧本", error.getMessage());
    }

    @Test
    void generateWithLlmWritesScript() {
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(textService.getIfAvailable()).thenReturn(dramaTextService);
        when(textChatGateway.llmAvailable(21L)).thenReturn(true);
        when(dramaTextService.engine(21L)).thenReturn(DramaTextService.ENGINE_LLM);
        when(dramaTextService.parseScript(anyLong(), anyString())).thenReturn(sampleDoc());

        DramaScriptImportReq req = new DramaScriptImportReq();
        req.setSourceText("洛白夏安办案");
        DramaEpisodeResp resp = service.generateScript(31L, req);

        assertEquals("洛白夏安办案", episode.getSourceText());
        assertEquals(DramaContract.ENGINE_LLM, episode.getEngine());
        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, resp.getStatus());
        assertTrue(resp.getCanGenerate());
        verify(episodeMapper).updateById(episode);
    }

    @Test
    void lockWithoutContentRejected() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        BizException error = assertThrows(BizException.class, () -> service.lockScript(31L));
        assertEquals("请先导入或生成剧本", error.getMessage());
    }

    @Test
    void lockWithSourceTextOnlySucceeds() {
        DramaEpisode episode = ownedEpisode();
        episode.setSourceText("第一场原文");
        episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        episode.setEngine(DramaContract.ENGINE_PENDING);
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        DramaEpisodeResp resp = service.lockScript(31L);
        assertEquals(DramaContract.STATUS_LOCKED, resp.getStatus());
        verify(episodeMapper).updateById(episode);
    }

    @Test
    void saveAfterLockExpiresDownstreamAssets() {
        DramaEpisode episode = ownedEpisode();
        episode.setSourceText("第一场原文");
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episode.setEngine(DramaContract.ENGINE_PENDING);
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        DramaAsset locked = new DramaAsset();
        locked.setId(9L);
        locked.setEpisodeId(31L);
        locked.setStatus(DramaContract.STATUS_LOCKED);
        when(assetMapper.selectList(any())).thenReturn(List.of(locked));

        DramaScriptSaveReq req = new DramaScriptSaveReq();
        req.setSourceText("第一场原文（改了台词）");
        DramaEpisodeResp resp = service.saveScript(31L, req);

        assertEquals(DramaContract.STATUS_PENDING_CONFIRM, resp.getStatus());
        assertEquals(DramaContract.STATUS_EXPIRED, locked.getStatus());
        verify(assetMapper).updateById(locked);
        verify(storyboardService).expireByEpisode(31L);
    }

    @Test
    void lockIsIdempotent() {
        DramaEpisode episode = ownedEpisode();
        episode.setSourceText("原文");
        episode.setStatus(DramaContract.STATUS_LOCKED);
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        assertEquals(DramaContract.STATUS_LOCKED, service.lockScript(31L).getStatus());
        org.mockito.Mockito.verify(episodeMapper, org.mockito.Mockito.never()).updateById(episode);
    }

    private static DramaEpisode ownedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setEpisodeNumber(1);
        episode.setName("第 1 集");
        episode.setStatus(DramaContract.STATUS_NOT_STARTED);
        episode.setEngine(DramaContract.ENGINE_PENDING);
        return episode;
    }

    private static ScriptDoc sampleDoc() {
        ScriptDoc doc = new ScriptDoc();
        doc.setTitle("幻梦侦探社");
        doc.setLogline("洛白被卷入入梦案件");
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("幻梦侦探社 日 内");
        scene.setCharacters(List.of("洛白", "夏安", "局长"));
        scene.setProps(List.of("古铜怀表"));
        ScriptDoc.Dialogue line = new ScriptDoc.Dialogue();
        line.setSpeaker("局长");
        line.setLine("洛老弟，别睡了。");
        scene.setDialogue(List.of(line));
        doc.setScenes(List.of(scene));
        return doc;
    }
}
