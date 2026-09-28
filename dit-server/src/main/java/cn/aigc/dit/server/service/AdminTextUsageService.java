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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminTextUsageService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TextUsageBillingMapper usageMapper;
    private final CreditLedgerMapper creditLedgerMapper;
    private final SysUserMapper sysUserMapper;
    private final AiModelMapper aiModelMapper;

    public PageQueryResp<AdminTextUsageResp> page(int pageNum, int pageSize, int days,
                                                  String operation, Long modelId, String keyword) {
        List<Long> userIds = matchingUserIds(keyword);
        if (userIds != null && userIds.isEmpty()) {
            return PageQueryResp.of(pageNum, pageSize, 0, List.of());
        }
        Page<TextUsageBilling> page = new Page<>(pageNum, pageSize);
        usageMapper.selectPage(page, query(days, operation, modelId, userIds)
                .orderByDesc(TextUsageBilling::getCreatedAt));
        List<TextUsageBilling> records = page.getRecords();
        Map<Long, SysUser> users = byId(
                records.stream().map(TextUsageBilling::getUserId).toList(), sysUserMapper::selectBatchIds);
        Map<Long, AiModel> models = byId(
                records.stream().map(TextUsageBilling::getModelId).toList(), aiModelMapper::selectBatchIds);
        List<AdminTextUsageResp> result = records.stream()
                .map(item -> toResp(item, users.get(item.getUserId()), models.get(item.getModelId())))
                .toList();
        return PageQueryResp.of(page.getCurrent(), page.getSize(), page.getTotal(), result);
    }

    public AdminTextUsageSummaryResp summary(int days, String operation, Long modelId, String keyword) {
        List<Long> userIds = matchingUserIds(keyword);
        List<TextUsageBilling> records = userIds != null && userIds.isEmpty()
                ? List.of()
                : usageMapper.selectList(query(days, operation, modelId, userIds));
        AdminTextUsageSummaryResp resp = new AdminTextUsageSummaryResp();
        resp.setTaskCount(records.size());
        for (TextUsageBilling item : records) {
            long prompt = safe(item.getPromptTokens());
            long completion = safe(item.getCompletionTokens());
            long cached = safe(item.getCachedPromptTokens());
            long expected = safe(item.getCreditCost());
            long ledger = ledgerCost(item.getBillingRef());
            resp.setPromptTokens(resp.getPromptTokens() + prompt);
            resp.setCompletionTokens(resp.getCompletionTokens() + completion);
            resp.setCachedPromptTokens(resp.getCachedPromptTokens() + cached);
            resp.setCreditCost(resp.getCreditCost() + expected);
            resp.setLedgerCost(resp.getLedgerCost() + ledger);
            if (ledger != expected) {
                resp.setExceptionCount(resp.getExceptionCount() + 1);
            }
        }
        return resp;
    }

    private LambdaQueryWrapper<TextUsageBilling> query(int days, String operation, Long modelId,
                                                        List<Long> userIds) {
        OffsetDateTime from = OffsetDateTime.now(ZONE).minusDays(Math.max(1, days));
        return new LambdaQueryWrapper<TextUsageBilling>()
                .ge(TextUsageBilling::getCreatedAt, from)
                .eq(StringUtils.hasText(operation), TextUsageBilling::getOperation, operation)
                .eq(modelId != null, TextUsageBilling::getModelId, modelId)
                .in(userIds != null && !userIds.isEmpty(), TextUsageBilling::getUserId, userIds);
    }

    private List<Long> matchingUserIds(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String value = keyword.trim();
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .like(SysUser::getUsername, value)
                        .or().like(SysUser::getDisplayName, value)
                        .or().like(SysUser::getEmail, value))
                .stream().map(SysUser::getId).toList();
    }

    private AdminTextUsageResp toResp(TextUsageBilling item, SysUser user, AiModel model) {
        AdminTextUsageResp resp = new AdminTextUsageResp();
        resp.setId(item.getId());
        resp.setBillingRef(item.getBillingRef());
        resp.setUserId(item.getUserId());
        resp.setUsername(user == null ? null
                : (StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername()));
        resp.setEmail(user == null ? null : user.getEmail());
        resp.setSeriesId(item.getSeriesId());
        resp.setModelId(item.getModelId());
        resp.setModelName(model == null ? null : model.getName());
        resp.setOperation(item.getOperation());
        resp.setPromptTokens(safe(item.getPromptTokens()));
        resp.setCompletionTokens(safe(item.getCompletionTokens()));
        resp.setCachedPromptTokens(safe(item.getCachedPromptTokens()));
        resp.setTotalTokens(resp.getPromptTokens() + resp.getCompletionTokens());
        resp.setCreditCost(safe(item.getCreditCost()));
        resp.setLedgerCost(ledgerCost(item.getBillingRef()));
        resp.setReconciliationStatus(resp.getLedgerCost().equals(resp.getCreditCost())
                ? "MATCHED" : (resp.getLedgerCost() == 0 ? "MISSING_LEDGER" : "AMOUNT_MISMATCH"));
        resp.setCreatedAt(item.getCreatedAt());
        return resp;
    }

    private long ledgerCost(String billingRef) {
        long amount = creditLedgerMapper.sumByRefId(billingRef);
        return amount < 0 ? -amount : 0L;
    }

    private static long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static <T> Map<Long, T> byId(Collection<Long> ids,
                                         Function<Collection<? extends java.io.Serializable>, List<T>> loader) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        return loader.apply(distinct).stream().collect(Collectors.toMap(item -> {
            if (item instanceof SysUser user) return user.getId();
            if (item instanceof AiModel model) return model.getId();
            throw new IllegalArgumentException("Unsupported entity");
        }, Function.identity()));
    }
}
