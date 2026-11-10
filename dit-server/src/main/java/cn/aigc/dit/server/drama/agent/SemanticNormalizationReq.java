package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptReferenceIndex;

/** Input for a single episode-level semantic normalization call. */
public record SemanticNormalizationReq(
        long seriesId,
        long episodeId,
        ScriptDoc script,
        ScriptReferenceIndex references,
        String memoryBlock,
        String repairFeedback
) {
    public SemanticNormalizationReq(long seriesId, long episodeId, ScriptDoc script,
                                    ScriptReferenceIndex references, String memoryBlock) {
        this(seriesId, episodeId, script, references, memoryBlock, null);
    }
}
