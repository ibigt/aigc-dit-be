package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "管理端仪表盘聚合")
public class DashboardResp {

    private Metrics metrics = new Metrics();
    private List<TrendPoint> trend = new ArrayList<>();
    private List<NamedShare> modelUsage = new ArrayList<>();
    private List<NamedShare> sources = new ArrayList<>();
    private List<Integer> hourly = new ArrayList<>();
    private Integer peakHour;
    private int periodDays;

    @Data
    public static class Metrics {
        private long todayRequests;
        private Double todayRequestsChange;
        private long activeUsers;
        private long newUsersThisWeek;
        private long enabledModels;
        private long totalModels;
        private long todayCreditCost;
        private Double successRate;
        private Double avgDurationSeconds;
    }

    @Data
    public static class TrendPoint {
        private String date;
        private long count;
    }

    @Data
    public static class NamedShare {
        private String name;
        private long count;
        private double percent;
    }
}
