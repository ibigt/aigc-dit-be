package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.mapper.stat.HourCount;
import cn.aigc.dit.server.mapper.stat.NamedCount;
import cn.aigc.dit.server.model.resp.DashboardResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final SysUserMapper sysUserMapper;

    public DashboardResp aggregate(int days) {
        int period = Math.min(90, Math.max(1, days));
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        LocalDate today = now.toLocalDate();
        OffsetDateTime todayStart = today.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime tomorrowStart = todayStart.plusDays(1);
        OffsetDateTime yesterdayStart = todayStart.minusDays(1);
        OffsetDateTime weekStart = today.minusDays(6).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime periodStart = today.minusDays(period - 1L).atStartOfDay(ZONE).toOffsetDateTime();

        DashboardResp resp = new DashboardResp();
        resp.setPeriodDays(period);

        DashboardResp.Metrics metrics = resp.getMetrics();
        long todayRequests = genTaskMapper.countCreatedBetween(todayStart, tomorrowStart);
        long yesterdayRequests = genTaskMapper.countCreatedBetween(yesterdayStart, todayStart);
        metrics.setTodayRequests(todayRequests);
        metrics.setTodayRequestsChange(percentChange(todayRequests, yesterdayRequests));
        metrics.setActiveUsers(genTaskMapper.countDistinctUsersBetween(weekStart, tomorrowStart));
        metrics.setNewUsersThisWeek(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .ge(SysUser::getCreatedAt, weekStart)));
        long totalModels = aiModelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                .ne(AiModel::getCategory, "tool"));
        long enabledModels = aiModelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                .ne(AiModel::getCategory, "tool")
                .eq(AiModel::getEnabled, true));
        metrics.setTotalModels(totalModels);
        metrics.setEnabledModels(enabledModels);
        metrics.setTodayCreditCost(genTaskMapper.sumCreditBetween(todayStart, tomorrowStart));

        Map<String, Long> statusCounts = toCountMap(genTaskMapper.countByStatusBetween(periodStart, tomorrowStart));
        long succeeded = statusCounts.getOrDefault(TaskStatus.SUCCEEDED, 0L);
        long failed = statusCounts.getOrDefault(TaskStatus.FAILED, 0L);
        long finished = succeeded + failed;
        metrics.setSuccessRate(finished == 0 ? 0.0 : round1(succeeded * 100.0 / finished));
        Double avg = genTaskMapper.avgDurationSecondsBetween(periodStart, tomorrowStart);
        metrics.setAvgDurationSeconds(avg == null ? 0.0 : round1(avg));

        Map<String, Long> byDay = toCountMap(genTaskMapper.countByDayBetween(periodStart, tomorrowStart));
        List<DashboardResp.TrendPoint> trend = new ArrayList<>();
        for (int i = period - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            DashboardResp.TrendPoint point = new DashboardResp.TrendPoint();
            point.setDate(day.format(DAY));
            point.setCount(byDay.getOrDefault(day.format(DAY), 0L));
            trend.add(point);
        }
        resp.setTrend(trend);

        resp.setModelUsage(toShares(genTaskMapper.countByModelBetween(periodStart, tomorrowStart), 4, "其他模型"));
        resp.setSources(toShares(genTaskMapper.countBySourceBetween(periodStart, tomorrowStart), 8, "其他"));

        int[] hourly = new int[24];
        for (HourCount row : genTaskMapper.countByHourBetween(periodStart, tomorrowStart)) {
            if (row.getHour() != null && row.getHour() >= 0 && row.getHour() < 24) {
                hourly[row.getHour()] = row.getCnt() == null ? 0 : row.getCnt().intValue();
            }
        }
        List<Integer> hourlyList = new ArrayList<>(24);
        int peak = 0;
        int peakHour = 0;
        for (int h = 0; h < 24; h++) {
            hourlyList.add(hourly[h]);
            if (hourly[h] > peak) {
                peak = hourly[h];
                peakHour = h;
            }
        }
        resp.setHourly(hourlyList);
        resp.setPeakHour(peak == 0 ? null : peakHour);
        return resp;
    }

    static List<DashboardResp.NamedShare> toShares(List<NamedCount> rows, int topN, String otherLabel) {
        long total = 0;
        List<NamedCount> safe = rows == null ? List.of() : rows;
        for (NamedCount row : safe) {
            total += nz(row.getCnt());
        }
        List<DashboardResp.NamedShare> shares = new ArrayList<>();
        long used = 0;
        int limit = Math.min(topN, safe.size());
        for (int i = 0; i < limit; i++) {
            NamedCount row = safe.get(i);
            long count = nz(row.getCnt());
            used += count;
            shares.add(share(row.getName(), count, total));
        }
        if (safe.size() > topN) {
            shares.add(share(otherLabel, total - used, total));
        }
        return shares;
    }

    static DashboardResp.NamedShare share(String name, long count, long total) {
        DashboardResp.NamedShare item = new DashboardResp.NamedShare();
        item.setName(name);
        item.setCount(count);
        item.setPercent(total == 0 ? 0.0 : round1(count * 100.0 / total));
        return item;
    }

    static Double percentChange(long current, long baseline) {
        if (baseline == 0) {
            return current == 0 ? 0.0 : 100.0;
        }
        return round1((current - baseline) * 100.0 / baseline);
    }

    static Map<String, Long> toCountMap(List<NamedCount> rows) {
        Map<String, Long> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (NamedCount row : rows) {
            if (row.getName() != null) {
                map.put(row.getName(), nz(row.getCnt()));
            }
        }
        return map;
    }

    static long nz(Long value) {
        return value == null ? 0L : value;
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
