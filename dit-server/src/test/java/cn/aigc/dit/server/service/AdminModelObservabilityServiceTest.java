package cn.aigc.dit.server.service;

import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.stat.ModelObservabilityRow;
import cn.aigc.dit.server.model.resp.AdminModelObservabilityResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminModelObservabilityServiceTest {
    @Test
    void assemblesWeightedMetricsAndSuccessRate() {
        ModelObservabilityRow first = row(10, 8, 2, 12.4, 80);
        first.setModelName("视频模型 A");
        first.setCategory("video");
        ModelObservabilityRow second = row(5, 5, 0, 20.0, 30);
        second.setModelName("图片模型 B");
        second.setCategory("image");

        AdminModelObservabilityResp result = AdminModelObservabilityService.assemble(List.of(first, second));

        assertEquals(15, result.getTotal());
        assertEquals(13, result.getSucceeded());
        assertEquals(2, result.getFailed());
        assertEquals(86.7, result.getSuccessRate());
        assertEquals(14.9, result.getAvgDurationSeconds());
        assertEquals(110, result.getCreditCost());
        assertEquals("视频生成", result.getModels().get(0).getCategoryLabel());
    }

    @Test
    void normalizesSupportedCategoryAndIgnoresUnknownValue() {
        assertEquals("audio", AdminModelObservabilityService.normalizeCategory(" AUDIO "));
        assertEquals(null, AdminModelObservabilityService.normalizeCategory("other"));
        assertEquals(null, AdminModelObservabilityService.normalizeCategory("all"));
    }

    @Test
    void allCategoriesCombineGenerationAndTextUsage() {
        GenTaskMapper mapper = mock(GenTaskMapper.class);
        when(mapper.selectModelObservability(any(), any(), isNull(), isNull())).thenReturn(List.of(row(2, 1, 1, 5, 10)));
        when(mapper.selectTextModelObservability(any(), any(), isNull())).thenReturn(List.of(row(3, 3, 0, 0, 6)));

        AdminModelObservabilityResp result = new AdminModelObservabilityService(mapper).get(7, null, null);

        assertEquals(5, result.getTotal());
        assertEquals(2, result.getModels().size());
        verify(mapper).selectModelObservability(any(), any(), isNull(), isNull());
        verify(mapper).selectTextModelObservability(any(), any(), isNull());
    }

    private static ModelObservabilityRow row(long total, long succeeded, long failed, double avg, long credits) {
        ModelObservabilityRow row = new ModelObservabilityRow();
        row.setTotal(total);
        row.setSucceeded(succeeded);
        row.setFailed(failed);
        row.setAvgDurationSeconds(avg);
        row.setP95DurationSeconds(avg * 2);
        row.setCreditCost(credits);
        return row;
    }
}
