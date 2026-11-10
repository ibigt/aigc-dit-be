package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;

public interface SemanticNormalizationAgent
        extends ScriptAgent<SemanticNormalizationReq, SemanticNormalizationResult> {
    @Override
    default String agentType() {
        return "semantic_normalization";
    }
}
