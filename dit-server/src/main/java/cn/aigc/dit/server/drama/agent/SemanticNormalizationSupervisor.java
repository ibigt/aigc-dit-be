package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;

public interface SemanticNormalizationSupervisor extends SupervisorAgent<SemanticNormalizationResult> {
    @Override
    default String supervisorType() {
        return "semantic_normalization_sup";
    }
}
