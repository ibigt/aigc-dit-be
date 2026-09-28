package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.server.drama.text.TextBillingSession;
import cn.aigc.dit.server.drama.text.TextChatResult;
import cn.aigc.dit.server.entity.TextUsageBilling;
import cn.aigc.dit.server.mapper.TextUsageBillingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextBillingServiceTest {

    @Mock
    private TextUsageBillingMapper usageMapper;
    @Mock
    private CreditService creditService;

    private TextBillingService service;

    @BeforeEach
    void setUp() {
        service = new TextBillingService(usageMapper, creditService);
    }

    @Test
    void settlesOneLedgerAndIgnoresDuplicateBillingRef() {
        TextBillingSession session = session(Map.of(
                "unit", "千Token",
                "sale", Map.of("input", 1, "output", 2, "cache", 0.2)
        ));
        session.record(TextChatResult.of("ok", "m", "stop", 1000, 500, 200));
        when(usageMapper.insertIfAbsent(any())).thenReturn(1, 0);

        service.settle(session);
        service.settle(session);

        ArgumentCaptor<TextUsageBilling> captor = ArgumentCaptor.forClass(TextUsageBilling.class);
        verify(usageMapper, times(2)).insertIfAbsent(captor.capture());
        TextUsageBilling usage = captor.getAllValues().get(0);
        assertEquals(1000, usage.getPromptTokens());
        assertEquals(500, usage.getCompletionTokens());
        assertEquals(200, usage.getCachedPromptTokens());
        assertEquals(2, usage.getCreditCost());
        verify(creditService).change(eq(7L), eq(-2L), eq(CreditReason.TEXT_GENERATE),
                eq(session.getBillingRef()), any());
    }

    @Test
    void recordsFreeUsageWithoutWritingZeroLedger() {
        TextBillingSession session = session(Map.of("unit", "次", "sale", 0));
        session.record(TextChatResult.of("ok", "m", "stop", 10, 5, 0));
        when(usageMapper.insertIfAbsent(any())).thenReturn(1);

        service.settle(session);

        verify(usageMapper).insertIfAbsent(any());
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    private static TextBillingSession session(Map<String, Object> pricing) {
        return new TextBillingSession(7L, 21L, 11L, "SCRIPT_PARSE", pricing);
    }
}
