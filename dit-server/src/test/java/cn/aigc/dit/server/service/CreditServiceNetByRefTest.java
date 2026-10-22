package cn.aigc.dit.server.service;

import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.RechargeOrderMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.pay.PayChannelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditServiceNetByRefTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private CreditLedgerMapper creditLedgerMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;

    private CreditService service;

    @BeforeEach
    void setUp() {
        service = new CreditService(sysUserMapper, creditLedgerMapper, rechargeOrderMapper, new PayChannelRouter(List.of(), true), new TransactionTemplate());
    }

    @Test
    void netByRefUsesLedgerSumForRefundGuard() {
        when(creditLedgerMapper.sumByRefId("8")).thenReturn(-40L);
        assertEquals(-40L, service.netByRef("8"));
        assertFalse(service.hasRefund("8"));

        when(creditLedgerMapper.sumByRefId("8")).thenReturn(0L);
        assertEquals(0L, service.netByRef("8"));
        assertTrue(service.hasRefund("8"));
    }

    @Test
    void blankRefIsTreatedAsZero() {
        assertEquals(0L, service.netByRef(" "));
        assertTrue(service.hasRefund(null));
    }
}
