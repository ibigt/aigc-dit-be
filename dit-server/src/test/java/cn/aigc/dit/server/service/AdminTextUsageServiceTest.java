package cn.aigc.dit.server.service;

import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.entity.TextUsageBilling;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.mapper.TextUsageBillingMapper;
import cn.aigc.dit.server.model.resp.AdminTextUsageResp;
import cn.aigc.dit.server.model.resp.AdminTextUsageSummaryResp;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTextUsageServiceTest {

    @Mock private TextUsageBillingMapper usageMapper;
    @Mock private CreditLedgerMapper creditLedgerMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AiModelMapper aiModelMapper;

    private AdminTextUsageService service;

    @BeforeEach
    void setUp() {
        service = new AdminTextUsageService(
                usageMapper, creditLedgerMapper, sysUserMapper, aiModelMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageAddsUserModelAndReconciliationDetails() {
        TextUsageBilling usage = usage(1L, "TXT-1", 100, 40, 20, 3);
        when(usageMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<TextUsageBilling> page = invocation.getArgument(0);
            page.setRecords(List.of(usage));
            page.setTotal(1);
            return page;
        });
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("creator");
        user.setDisplayName("创作者");
        user.setEmail("creator@example.com");
        AiModel model = new AiModel();
        model.setId(11L);
        model.setName("文本模型");
        when(sysUserMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user));
        when(aiModelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(model));
        when(creditLedgerMapper.sumByRefId("TXT-1")).thenReturn(-3L);

        PageQueryResp<AdminTextUsageResp> page = service.page(1, 20, 30, null, null, null);

        assertEquals(1, page.getTotalNum());
        AdminTextUsageResp item = page.getResult().get(0);
        assertEquals("创作者", item.getUsername());
        assertEquals("文本模型", item.getModelName());
        assertEquals(140, item.getTotalTokens());
        assertEquals(3, item.getLedgerCost());
        assertEquals("MATCHED", item.getReconciliationStatus());
    }

    @Test
    void summaryAggregatesTokensCreditsAndExceptions() {
        TextUsageBilling first = usage(1L, "TXT-1", 100, 40, 20, 3);
        TextUsageBilling second = usage(2L, "TXT-2", 200, 60, 0, 2);
        when(usageMapper.selectList(any())).thenReturn(List.of(first, second));
        when(creditLedgerMapper.sumByRefId("TXT-1")).thenReturn(-3L);
        when(creditLedgerMapper.sumByRefId("TXT-2")).thenReturn(0L);

        AdminTextUsageSummaryResp summary = service.summary(30, null, null, null);

        assertEquals(2, summary.getTaskCount());
        assertEquals(300, summary.getPromptTokens());
        assertEquals(100, summary.getCompletionTokens());
        assertEquals(20, summary.getCachedPromptTokens());
        assertEquals(5, summary.getCreditCost());
        assertEquals(3, summary.getLedgerCost());
        assertEquals(1, summary.getExceptionCount());
    }

    @Test
    void unmatchedUserKeywordReturnsEmptyWithoutScanningUsage() {
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        PageQueryResp<AdminTextUsageResp> page = service.page(1, 20, 30, null, null, "nobody");

        assertEquals(0, page.getTotalNum());
        verify(usageMapper, never()).selectPage(any(), any());
    }

    private static TextUsageBilling usage(long id, String ref, long prompt, long completion,
                                           long cached, long cost) {
        TextUsageBilling usage = new TextUsageBilling();
        usage.setId(id);
        usage.setBillingRef(ref);
        usage.setUserId(7L);
        usage.setSeriesId(21L);
        usage.setModelId(11L);
        usage.setOperation("SCRIPT_PARSE");
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setCachedPromptTokens(cached);
        usage.setCreditCost(cost);
        usage.setCreatedAt(OffsetDateTime.now());
        return usage;
    }
}
