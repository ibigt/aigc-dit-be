package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.model.resp.AdminGenTaskResp;
import cn.aigc.dit.server.model.resp.AdminQueueSummaryResp;
import cn.aigc.dit.server.service.AdminGenTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Tag(name = "管理端-请求日志与任务队列")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminGenTaskController {

    private final AdminGenTaskService adminGenTaskService;

    @Operation(summary = "请求日志分页")
    @GetMapping("/api/admin/requests")
    public CommonResult<PageQueryResp<AdminGenTaskResp>> pageRequests(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer hours) {
        int safePage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int safeSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return CommonResult.success(adminGenTaskService.pageRequests(safePage, safeSize, keyword, status, hours));
    }

    @Operation(summary = "导出请求日志 CSV")
    @GetMapping("/api/admin/requests/export")
    public ResponseEntity<byte[]> exportRequests(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer hours) {
        byte[] csv = adminGenTaskService.exportRequestsCsv(keyword, status, hours);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"request-logs.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @Operation(summary = "任务队列分页")
    @GetMapping("/api/admin/queue")
    public CommonResult<PageQueryResp<AdminGenTaskResp>> pageQueue(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer days) {
        int safePage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int safeSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return CommonResult.success(adminGenTaskService.pageQueue(safePage, safeSize, status, days));
    }

    @Operation(summary = "任务队列汇总")
    @GetMapping("/api/admin/queue/summary")
    public CommonResult<AdminQueueSummaryResp> queueSummary() {
        return CommonResult.success(adminGenTaskService.summary());
    }

    @Operation(summary = "重试失败任务")
    @PostMapping("/api/admin/queue/{id}/retry")
    public CommonResult<AdminGenTaskResp> retry(@PathVariable Long id) {
        return CommonResult.success(adminGenTaskService.retry(id));
    }

    @Operation(summary = "取消排队或运行中任务")
    @PostMapping("/api/admin/queue/{id}/cancel")
    public CommonResult<AdminGenTaskResp> cancel(@PathVariable Long id) {
        return CommonResult.success(adminGenTaskService.cancel(id));
    }
}
