package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.server.drama.text.TextBillingSession;
import cn.aigc.dit.server.entity.TextUsageBilling;
import cn.aigc.dit.server.mapper.TextUsageBillingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TextBillingService {

    private final TextUsageBillingMapper usageMapper;
    private final CreditService creditService;

    @Transactional
    public void settle(TextBillingSession session) {
        if (session == null || !session.hasUsage()) {
            return;
        }
        long cost = session.creditCost();
        TextUsageBilling usage = new TextUsageBilling();
        usage.setBillingRef(session.getBillingRef());
        usage.setUserId(session.getUserId());
        usage.setSeriesId(session.getSeriesId());
        usage.setModelId(session.getModelId());
        usage.setOperation(session.getOperation());
        usage.setPromptTokens(session.getPromptTokens());
        usage.setCompletionTokens(session.getCompletionTokens());
        usage.setCachedPromptTokens(session.getCachedPromptTokens());
        usage.setCreditCost(cost);
        if (usageMapper.insertIfAbsent(usage) == 0) {
            return;
        }
        if (cost > 0) {
            creditService.change(session.getUserId(), -cost, CreditReason.TEXT_GENERATE,
                    session.getBillingRef(), remark(session, cost));
        }
    }

    private static String remark(TextBillingSession session, long cost) {
        return "文本模型·" + operationLabel(session.getOperation())
                + "：输入 " + session.getPromptTokens()
                + " / 输出 " + session.getCompletionTokens()
                + " / 缓存 " + session.getCachedPromptTokens()
                + " Token，扣除 " + cost + " 积分";
    }

    private static String operationLabel(String operation) {
        return switch (operation) {
            case "SCRIPT_PARSE" -> "剧本分场";
            case "ASSET_ENRICH" -> "素材增强";
            case "SHOT_DRAFT" -> "分镜草稿";
            default -> "文本任务";
        };
    }
}
