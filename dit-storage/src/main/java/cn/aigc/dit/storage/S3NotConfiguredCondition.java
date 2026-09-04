package cn.aigc.dit.storage;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * {@code dit.s3.endpoint} 为空时使用本地对象存储。
 */
public class S3NotConfiguredCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String endpoint = context.getEnvironment().getProperty("dit.s3.endpoint");
        if (!StringUtils.hasText(endpoint)) {
            return ConditionOutcome.match("dit.s3.endpoint is blank, use local storage");
        }
        return ConditionOutcome.noMatch("dit.s3.endpoint=" + endpoint);
    }
}
