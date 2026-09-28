package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.stat.ModelObservabilityRow;
import cn.aigc.dit.server.model.resp.AdminModelObservabilityResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminModelObservabilityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final GenTaskMapper genTaskMapper;

    public AdminModelObservabilityResp get(Integer days, String category, Long modelId) {
        int safeDays = days == null ? 7 : Math.max(1, Math.min(90, days));
        OffsetDateTime end = OffsetDateTime.now(ZONE).plusSeconds(1);
        OffsetDateTime start = end.minusDays(safeDays);
        String normalizedCategory = normalizeCategory(category);
        List<ModelObservabilityRow> rows = new ArrayList<>();
        if (!ModelCategory.TEXT.equals(normalizedCategory)) {
            rows.addAll(genTaskMapper.selectModelObservability(start, end, normalizedCategory, modelId));
        }
        if (normalizedCategory == null || ModelCategory.TEXT.equals(normalizedCategory)) {
            rows.addAll(genTaskMapper.selectTextModelObservability(start, end, modelId));
        }
        rows.sort((left, right) -> Long.compare(nz(right.getTotal()), nz(left.getTotal())));
        return assemble(rows);
    }

    static AdminModelObservabilityResp assemble(List<ModelObservabilityRow> rows) {
        AdminModelObservabilityResp resp = new AdminModelObservabilityResp();
        double durationSum = 0;
        long durationWeight = 0;
        if (rows == null) {
            return resp;
        }
        for (ModelObservabilityRow row : rows) {
            if (row == null) continue;
            AdminModelObservabilityResp.ModelMetric item = new AdminModelObservabilityResp.ModelMetric();
            long total = nz(row.getTotal());
            long succeeded = nz(row.getSucceeded());
            long failed = nz(row.getFailed());
            long finished = succeeded + failed;
            item.setModelId(row.getModelId());
            item.setModelName(row.getModelName());
            item.setProvider(row.getProvider());
            item.setCategory(row.getCategory());
            item.setCategoryLabel(AdminGenTaskService.categoryLabel(row.getCategory()));
            item.setTotal(total);
            item.setSucceeded(succeeded);
            item.setFailed(failed);
            item.setCancelled(nz(row.getCancelled()));
            item.setRunning(nz(row.getRunning()));
            item.setPending(nz(row.getPending()));
            item.setCreditCost(nz(row.getCreditCost()));
            item.setSuccessRate(finished == 0 ? 0 : round1(succeeded * 100.0 / finished));
            item.setAvgDurationSeconds(round1(nz(row.getAvgDurationSeconds())));
            item.setP95DurationSeconds(round1(nz(row.getP95DurationSeconds())));
            item.setAvgWaitSeconds(round1(nz(row.getAvgWaitSeconds())));
            item.setLastCalledAt(row.getLastCalledAt() == null ? null : row.getLastCalledAt().toString());
            resp.getModels().add(item);

            resp.setTotal(resp.getTotal() + total);
            resp.setSucceeded(resp.getSucceeded() + succeeded);
            resp.setFailed(resp.getFailed() + failed);
            resp.setRunning(resp.getRunning() + item.getRunning());
            resp.setPending(resp.getPending() + item.getPending());
            resp.setCreditCost(resp.getCreditCost() + item.getCreditCost());
            if (finished > 0) {
                durationSum += item.getAvgDurationSeconds() * finished;
                durationWeight += finished;
            }
        }
        long finished = resp.getSucceeded() + resp.getFailed();
        resp.setSuccessRate(finished == 0 ? 0 : round1(resp.getSucceeded() * 100.0 / finished));
        resp.setAvgDurationSeconds(durationWeight == 0 ? 0 : round1(durationSum / durationWeight));
        return resp;
    }

    static String normalizeCategory(String category) {
        if (!StringUtils.hasText(category) || "all".equalsIgnoreCase(category.trim())) return null;
        String value = category.trim().toLowerCase(Locale.ROOT);
        if (ModelCategory.IMAGE.equals(value) || ModelCategory.VIDEO.equals(value)
                || ModelCategory.AUDIO.equals(value) || ModelCategory.TEXT.equals(value)) {
            return value;
        }
        return null;
    }

    private static long nz(Long value) { return value == null ? 0 : value; }
    private static double nz(Double value) { return value == null ? 0 : Math.max(0, value); }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }
}
