package cn.aigc.dit.server.service;

import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.resp.CreationSessionResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreationServiceTest {

    @Mock
    private CreationSessionMapper sessionMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private AssetService assetService;
    @Mock
    private DramaSeriesMapper dramaSeriesMapper;
    @Mock
    private CreationMapper creationMapper;

    private CreationService service;

    @BeforeEach
    void setUp() {
        service = new CreationService(sessionMapper, genTaskMapper, aiModelMapper, assetService, dramaSeriesMapper);
        ReflectionTestUtils.setField(service, "baseMapper", creationMapper);
    }

    @Test
    void sessionWithNullModelIdDoesNotCrash() {
        CreationSession session = new CreationSession();
        session.setId(9L);
        session.setUserId(1L);
        session.setTitle("会话");
        when(creationMapper.selectList(any())).thenReturn(List.of());
        GenTask orphan = new GenTask();
        orphan.setId(66L);
        orphan.setUserId(1L);
        orphan.setModelId(null);
        orphan.setPrompt("test");
        orphan.setParams(Map.of("sessionId", 9));
        when(genTaskMapper.selectList(any())).thenReturn(List.of(orphan));

        CreationSessionResp resp = ReflectionTestUtils.invokeMethod(service, "toSessionResp", session, true);

        assertEquals(1, resp.getTasks().size());
        assertNull(resp.getTasks().get(0).getModelId());
    }

    @Test
    void modelNameOfIgnoresNullKeyOnImmutableMap() {
        assertNull(CreationService.modelNameOf(Map.of(), null));
        assertNull(CreationService.modelNameOf(Map.of(3L, "Seedance"), null));
        assertEquals("Seedance", CreationService.modelNameOf(Map.of(3L, "Seedance"), 3L));
    }

    @Test
    void fileFormatDerivesFromStorageKey() {
        assertEquals("WAV", CreationService.fileFormat("users/1/voice.wav"));
        assertEquals("MP3", CreationService.fileFormat("users/1/voice.mp3"));
        assertNull(CreationService.fileFormat("users/1/without-extension"));
        assertNull(CreationService.fileFormat(""));
        assertNull(CreationService.fileFormat(null));
    }

    @Test
    void titleFromPromptStripsPictureAnchors() {
        assertEquals("高精度二次元写实，半写实", CreationService.titleFromPrompt(
                "<Picture 1> <Picture 2> <Picture 3> 高精度二次元写实，半写实3D渲染"));
        assertEquals("未命名创作", CreationService.titleFromPrompt("<Picture 1> <Picture 2>"));
        assertEquals("雨后城市", CreationService.titleFromPrompt("雨后城市"));
    }

    @Test
    void projectFilterParsesKindAndId() {
        CreationService.ProjectFilter all = CreationService.ProjectFilter.parse(null);
        CreationService.ProjectFilter drama = CreationService.ProjectFilter.parse("drama:18");
        CreationService.ProjectFilter quick = CreationService.ProjectFilter.parse("quick");
        CreationService.ProjectFilter dramaKind = CreationService.ProjectFilter.parse("drama");
        CreationService.ProjectFilter none = CreationService.ProjectFilter.parse("none");
        CreationService.ProjectFilter invalid = CreationService.ProjectFilter.parse("workflow");

        assertEquals(true, all.all());
        assertEquals("drama", drama.kind());
        assertEquals(18L, drama.id());
        assertEquals("quick", quick.kind());
        assertNull(quick.id());
        assertEquals(false, quick.all());
        assertEquals("drama", dramaKind.kind());
        assertNull(dramaKind.id());
        assertEquals(false, dramaKind.all());
        assertEquals(true, none.none());
        assertEquals(true, invalid.all());
    }
}
