package cn.aigc.dit.server.mapper;

import cn.aigc.dit.server.entity.TextUsageBilling;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TextUsageBillingMapper extends BaseMapper<TextUsageBilling> {

    @Insert("""
            INSERT INTO text_usage_billing
              (billing_ref, user_id, series_id, model_id, operation, prompt_tokens,
               completion_tokens, cached_prompt_tokens, credit_cost)
            VALUES
              (#{billingRef}, #{userId}, #{seriesId}, #{modelId}, #{operation}, #{promptTokens},
               #{completionTokens}, #{cachedPromptTokens}, #{creditCost})
            ON CONFLICT (billing_ref) DO NOTHING
            """)
    int insertIfAbsent(TextUsageBilling usage);
}
