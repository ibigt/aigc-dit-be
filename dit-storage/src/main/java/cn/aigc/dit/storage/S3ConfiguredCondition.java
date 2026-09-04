package cn.aigc.dit.storage;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * {@code dit.s3.endpoint} 非空时才装配 S3 客户端，避免空字符串误触发。
 */
public class S3ConfiguredCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String endpoint = context.getEnvironment().getProperty("dit.s3.endpoint");
        if (StringUtils.hasText(endpoint)) {
            return ConditionOutcome.match("dit.s3.endpoint=" + endpoint);
        }
        return ConditionOutcome.noMatch("dit.s3.endpoint is blank");
    }
}
