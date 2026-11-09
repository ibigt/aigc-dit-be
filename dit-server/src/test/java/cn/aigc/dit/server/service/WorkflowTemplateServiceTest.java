package cn.aigc.dit.server.service;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.Workflow;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.WorkflowMapper;
import cn.aigc.dit.server.model.req.WorkflowSaveReq;
import cn.aigc.dit.server.model.resp.WorkflowResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceTest {

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private AiModelMapper aiModelMapper;

    private WorkflowService service;

    @BeforeAll
    static void initLambdaCache() {
        // Wrapper 的 lambda 条件需要实体元数据，脱离 Spring 上下文时手动初始化。
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Workflow.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowMapper, aiModelMapper);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Workflow template(String title, String code, String category, boolean builtin, String demoKey) {
        Workflow item = new Workflow();
        item.setId((long) Math.abs(title.hashCode()));
        item.setTitle(title);
        item.setDescription(title + "描述");
        item.setType("video");
        item.setBuiltin(builtin);
        item.setDemoKey(demoKey);
        item.setTemplateCode(code);
        item.setCategory(category);
        item.setTags(List.of("图生视频", "多参考"));
        item.setCoverPath("/media/cover.webp");
        item.setVersion("1.0");
        item.setModelAdapter("minimax-h3");
        item.setTopology(Map.of("nodes", List.of(Map.of("x", 1)), "edges", List.of()));
        item.setDefaultParams(Map.of("ratio", "16:9"));
        return item;
    }

    @Test
    void listTemplatesReturnsMetadataForOfficialAndCustomTemplates() {
        when(workflowMapper.selectList(any())).thenReturn(List.of(
                template("Minimax-H3 图生视频", "minimax-h3-i2v", "video", true, "image-to-video"),
                template("我的分镜模板", null, "image", false, null)));

        List<WorkflowResp> resp = service.listTemplates(null, null, null, null);

        assertEquals(2, resp.size());
        WorkflowResp official = resp.get(0);
        assertEquals("minimax-h3-i2v", official.getTemplateCode());
        assertEquals("video", official.getCategory());
        assertEquals(List.of("图生视频", "多参考"), official.getTags());
        assertEquals("/media/cover.webp", official.getCoverPath());
        assertEquals("1.0", official.getVersion());
        assertEquals("minimax-h3", official.getModelAdapter());
        assertNotNull(official.getTopology());
        assertEquals("16:9", official.getDefaultParams().get("ratio"));
        assertTrue(official.getBuiltin());
        assertEquals("我的分镜模板", resp.get(1).getTitle());
    }

    @Test
    void listTemplatesAppliesCategoryFilter() {
        when(workflowMapper.selectList(any())).thenReturn(List.of());

        service.listTemplates("image", null, null, null);

        LambdaQueryWrapper<Workflow> wrapper = capturedWrapper();
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("category"), "分类筛选必须落到 category 条件：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("image"), "分类值必须写入查询参数");
    }

    @Test
    void listTemplatesAppliesTagFilterWithJsonbContainment() {
        when(workflowMapper.selectList(any())).thenReturn(List.of());

        service.listTemplates(null, null, "多参考", null);

        LambdaQueryWrapper<Workflow> wrapper = capturedWrapper();
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("@>"), "标签筛选必须使用 jsonb 包含判断：" + sql);
        assertTrue(sql.contains("AS jsonb"), "jsonb 参数需要显式转换：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("[\"多参考\"]"), "标签值必须写入查询参数");
    }

    @Test
    void listTemplatesSearchesKeywordInsideJsonbTags() {
        when(workflowMapper.selectList(any())).thenReturn(List.of());

        service.listTemplates(null, "参考", null, null);

        LambdaQueryWrapper<Workflow> wrapper = capturedWrapper();
        assertTrue(wrapper.getTargetSql().contains("tags::text"), "关键字检索需要覆盖 jsonb 标签：" + wrapper.getTargetSql());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%参考%"), "关键字必须写入查询参数");
    }

    @Test
    void listTemplatesAppliesSourceFilter() {
        // 官方内置模板已下线：source=official 直接返回空列表，不再触发数据库查询
        assertTrue(service.listTemplates(null, null, null, "official").isEmpty());
        verify(workflowMapper, never()).selectList(any());

        // 其余来源（custom / 全部）落到「我的模板」查询；
        // 先取 targetSql 再读参数（MP 3.5.7 的查询参数在生成 SQL 时才惰性写入 paramNameValuePairs）
        when(workflowMapper.selectList(any())).thenReturn(List.of());
        service.listTemplates(null, null, null, "custom");
        LambdaQueryWrapper<Workflow> wrapper = capturedWrapper();
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("type ="), "自定义模板必须限定 type：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("template"), "type 参数必须为 template：" + sql + " " + wrapper.getParamNameValuePairs());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Workflow> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<Workflow>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @Test
    void listTemplateTagsAggregatesDistinctTags() {
        when(workflowMapper.selectList(any())).thenReturn(List.of(
                template("A", "a", "video", true, null),
                template("B", "b", "image", true, null)));

        List<String> tags = service.listTemplateTags();

        assertEquals(List.of("图生视频", "多参考"), tags);
    }

    @Test
    void getMineIncludesGraphForTemplateDetail() {
        // builtin 不再享受可读豁免：模板详情也必须落在当前用户名下才可读
        Workflow item = template("Minimax-H3 图生视频", "minimax-h3-i2v", "video", true, "image-to-video");
        item.setUserId(7L);
        item.setGraph(Map.of("nodes", List.of(), "edges", List.of()));
        when(workflowMapper.selectById(any())).thenReturn(item);

        WorkflowResp resp = service.getMine(item.getId());

        assertNotNull(resp.getGraph());
        assertEquals("minimax-h3-i2v", resp.getTemplateCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateKeepsCarriedOutputsWhenStaleClientOmitsThemButAllowsFreshOverrides() {
        Map<String, Object> carried = Map.of("type", "video", "storageKey", "users/7/outputs/2452/1.mp4");
        Map<String, Object> foreign = Map.of("type", "video", "storageKey", "users/8/private.mp4");
        Map<String, Object> fresh = Map.of("type", "video", "storageKey", "users/7/outputs/2600/1.mp4");
        Workflow existing = new Workflow();
        existing.setId(81L); existing.setUserId(7L); existing.setType("import");
        existing.setGraph(new LinkedHashMap<>(Map.of("nodes", List.of(
                Map.of("id", "v", "data", Map.of("kind", "VideoGenerator"), "outputs", List.of(carried)),
                Map.of("id", "foreign", "data", Map.of("kind", "VideoGenerator"), "outputs", List.of(foreign)),
                Map.of("id", "rerun", "data", Map.of("kind", "VideoGenerator"), "outputs", List.of(carried))),
                "edges", List.of())));
        when(workflowMapper.selectById(81L)).thenReturn(existing);

        WorkflowSaveReq req = new WorkflowSaveReq();
        req.setTitle("视频剪辑测试"); req.setType("import");
        // 旧标签页只带空节点；rerun 节点带上了新一轮产物
        req.setGraph(Map.of("nodes", List.of(
                Map.of("id", "v", "data", Map.of("kind", "VideoGenerator")),
                Map.of("id", "foreign", "data", Map.of("kind", "VideoGenerator")),
                Map.of("id", "rerun", "data", Map.of("kind", "VideoGenerator"), "outputs", List.of(fresh))),
                "edges", List.of()));
        service.update(81L, req);

        ArgumentCaptor<Workflow> captor = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateById(captor.capture());
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) captor.getValue().getGraph().get("nodes");
        assertEquals(List.of(carried), nodes.get(0).get("outputs"), "旧标签页不能抹掉节点级产物");
        assertNull(nodes.get(1).get("outputs"), "别人的私有对象不得回填");
        assertEquals(List.of(fresh), nodes.get(2).get("outputs"), "客户端带来的新产物优先");
    }
}
