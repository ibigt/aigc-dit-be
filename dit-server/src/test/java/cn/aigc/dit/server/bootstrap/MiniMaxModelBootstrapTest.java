package cn.aigc.dit.server.bootstrap;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MiniMaxModelBootstrapTest {

    @Mock
    private AiModelMapper aiModelMapper;

    private MiniMaxModelBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new MiniMaxModelBootstrap(aiModelMapper);
    }

    @Test
    void insertsVerificationModelWhenMissing() {
        when(aiModelMapper.selectOne(any())).thenReturn(null);
        when(aiModelMapper.insert(any(AiModel.class))).thenReturn(1);

        bootstrap.run(new DefaultApplicationArguments());

        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);
        verify(aiModelMapper).insert(captor.capture());
        AiModel model = captor.getValue();
        assertEquals(MiniMaxModelBootstrap.MODEL_KEY, model.getModelKey());
        assertEquals(AdapterCatalog.MINIMAX_H3, model.getAdapterKey());
        assertEquals("ComfyUI", model.getProvider());
        assertEquals(MiniMaxModelBootstrap.DEFAULT_VERIFICATION_ENDPOINT, model.getEndpoint());
        assertEquals(List.of("video", "t2v", "r2v", "i2v"), model.getCapabilities());
        assertTrue(model.getEnabled());
        verify(aiModelMapper, never()).updateById(any(AiModel.class));
    }

    @Test
    void reconcilesLocalhostEndpointAndMissingR2v() {
        AiModel existing = new AiModel();
        existing.setId(9L);
        existing.setModelKey(MiniMaxModelBootstrap.MODEL_KEY);
        existing.setAdapterKey(AdapterCatalog.MINIMAX_H3);
        existing.setProvider("ComfyUI");
        existing.setEndpoint("http://127.0.0.1:8188");
        existing.setCapabilities(List.of("video", "t2v"));
        existing.setEnabled(false);
        when(aiModelMapper.selectOne(any())).thenReturn(existing);
        when(aiModelMapper.updateById(existing)).thenReturn(1);

        bootstrap.run(new DefaultApplicationArguments());

        assertEquals(MiniMaxModelBootstrap.DEFAULT_VERIFICATION_ENDPOINT, existing.getEndpoint());
        assertEquals(List.of("video", "t2v", "r2v", "i2v"), existing.getCapabilities());
        assertTrue(existing.getEnabled());
        verify(aiModelMapper).updateById(existing);
        verify(aiModelMapper, never()).insert(any(AiModel.class));
    }

    @Test
    void skipsUpdateWhenAlreadyAligned() {
        AiModel existing = new AiModel();
        existing.setId(9L);
        existing.setModelKey(MiniMaxModelBootstrap.MODEL_KEY);
        existing.setAdapterKey(AdapterCatalog.MINIMAX_H3);
        existing.setProvider("ComfyUI");
        existing.setEndpoint(MiniMaxModelBootstrap.DEFAULT_VERIFICATION_ENDPOINT);
        existing.setCapabilities(List.of("video", "t2v", "r2v", "i2v"));
        existing.setEnabled(true);
        when(aiModelMapper.selectOne(any())).thenReturn(existing);

        bootstrap.run(new DefaultApplicationArguments());

        verify(aiModelMapper, never()).updateById(any(AiModel.class));
        verify(aiModelMapper, never()).insert(any(AiModel.class));
    }

    @Test
    void reconcileDoesNotTouchYmlFallbackContract() {
        AiModel model = new AiModel();
        model.setAdapterKey("mock");
        model.setProvider("mock");
        model.setEndpoint("http://127.0.0.1:8188/");
        model.setCapabilities(List.of("video"));
        model.setEnabled(false);
        assertTrue(MiniMaxModelBootstrap.reconcile(model, MiniMaxModelBootstrap.DEFAULT_VERIFICATION_ENDPOINT));
        assertEquals(AdapterCatalog.MINIMAX_H3, model.getAdapterKey());
        assertFalse(MiniMaxModelBootstrap.reconcile(model, MiniMaxModelBootstrap.DEFAULT_VERIFICATION_ENDPOINT));
    }
}
