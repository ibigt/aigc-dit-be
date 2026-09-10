package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.generate.GenerateTaskPublisher;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateServiceTextModelTest {

    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private CreationService creationService;
    @Mock
    private AssetService assetService;
    @Mock
    private CreditService creditService;
    @Mock
    private GenerateTaskPublisher publisher;

    private GenerateService service;

    @BeforeEach
    void setUp() {
        service = new GenerateService(aiModelMapper, creationMapper, creationService, assetService, creditService, publisher);
        UserContext.set(new LoginUser(1L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void submitRejectsEnabledTextModel() {
        AiModel model = new AiModel();
        model.setId(9L);
        model.setEnabled(true);
        model.setCategory(ModelCategory.TEXT);
        model.setAdapterKey("agnes-text");
        when(aiModelMapper.selectById(9L)).thenReturn(model);

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(9L);
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt("洛白全身参考图");

        BizException error = assertThrows(BizException.class, () -> service.submit(req));
        assertEquals("该模型不能用于快速创作", error.getMessage());
    }
}
