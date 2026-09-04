package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.mapper.stat.HourCount;
import cn.aigc.dit.server.mapper.stat.NamedCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private SysUserMapper sysUserMapper;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(genTaskMapper, aiModelMapper, sysUserMapper);
    }

    @Test
    void emptyPeriodReturnsZeroMetrics() {
        when(genTaskMapper.countCreatedBetween(any(), any())).thenReturn(0L);
        when(genTaskMapper.countDistinctUsersBetween(any(), any())).thenReturn(0L);
        when(genTaskMapper.sumCreditBetween(any(), any())).thenReturn(0L);
        when(genTaskMapper.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(genTaskMapper.avgDurationSecondsBetween(any(), any())).thenReturn(null);
        when(genTaskMapper.countByDayBetween(any(), any())).thenReturn(List.of());
        when(genTaskMapper.countByHourBetween(any(), any())).thenReturn(List.of());
        when(genTaskMapper.countByModelBetween(any(), any())).thenReturn(List.of());
        when(genTaskMapper.countBySourceBetween(any(), any())).thenReturn(List.of());
        when(aiModelMapper.selectCount(any())).thenReturn(0L);
        when(sysUserMapper.selectCount(any())).thenReturn(0L);

        var resp = service.aggregate(14);
        assertEquals(14, resp.getPeriodDays());
        assertEquals(0, resp.getMetrics().getTodayRequests());
        assertEquals(0.0, resp.getMetrics().getSuccessRate());
        assertEquals(14, resp.getTrend().size());
        assertEquals(24, resp.getHourly().size());
        assertNull(resp.getPeakHour());
    }

    @Test
    void successRateIgnoresPending() {
        when(genTaskMapper.countCreatedBetween(any(), any())).thenReturn(10L);
        when(genTaskMapper.countDistinctUsersBetween(any(), any())).thenReturn(3L);
        when(genTaskMapper.sumCreditBetween(any(), any())).thenReturn(80L);
        when(genTaskMapper.countByStatusBetween(any(), any())).thenReturn(List.of(
                named(TaskStatus.SUCCEEDED, 8),
                named(TaskStatus.FAILED, 2),
                named(TaskStatus.PENDING, 5)
        ));
        when(genTaskMapper.avgDurationSecondsBetween(any(), any())).thenReturn(18.44);
        when(genTaskMapper.countByDayBetween(any(), any())).thenReturn(List.of());
        HourCount peak = new HourCount();
        peak.setHour(18);
        peak.setCnt(12L);
        when(genTaskMapper.countByHourBetween(any(), any())).thenReturn(List.of(peak));
        when(genTaskMapper.countByModelBetween(any(), any())).thenReturn(List.of(
                named("A", 6), named("B", 3), named("C", 1)
        ));
        when(genTaskMapper.countBySourceBetween(any(), any())).thenReturn(List.of(named("快速创作", 10)));
        when(aiModelMapper.selectCount(any())).thenReturn(5L);
        when(sysUserMapper.selectCount(any())).thenReturn(2L);

        var resp = service.aggregate(7);
        assertEquals(80.0, resp.getMetrics().getSuccessRate());
        assertEquals(18.4, resp.getMetrics().getAvgDurationSeconds());
        assertEquals(18, resp.getPeakHour());
        assertEquals(3, resp.getModelUsage().size());
        assertEquals(100.0, resp.getSources().get(0).getPercent());
    }

    @Test
    void percentChangeFromZeroBaseline() {
        assertEquals(100.0, DashboardService.percentChange(5, 0));
        assertEquals(0.0, DashboardService.percentChange(0, 0));
        assertEquals(-50.0, DashboardService.percentChange(5, 10));
    }

    private static NamedCount named(String name, long cnt) {
        NamedCount row = new NamedCount();
        row.setName(name);
        row.setCnt(cnt);
        return row;
    }
}
