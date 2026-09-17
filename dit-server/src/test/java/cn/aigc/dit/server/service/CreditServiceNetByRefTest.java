package cn.aigc.dit.server.service;

import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private CreditService service;

    @BeforeEach
    void setUp() {
        service = new CreditService(sysUserMapper, creditLedgerMapper);
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
