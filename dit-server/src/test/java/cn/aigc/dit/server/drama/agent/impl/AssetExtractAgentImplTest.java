package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.AssetExtractReq;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetExtractAgentImplTest {

    private final TextChatGateway gateway = mock(TextChatGateway.class);
    private final ScriptPromptResolver promptResolver = mock(ScriptPromptResolver.class);
    private final AssetExtractAgentImpl agent = new AssetExtractAgentImpl(gateway, promptResolver, new ObjectMapper());

    @Test
    void emptySkeletonFailsBeforeCallingTheModel() {
        when(promptResolver.resolve(any())).thenReturn("PROMPT");
        ScriptDoc script = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("空场");
        script.setScenes(List.of(scene));

        AgentResult<AssetDraftList> result = agent.executeWithSupervision(
                new AgentContext(1L, 225L, "session", "", "deepseek-chat"),
                new AssetExtractReq(1L, 225L, script, List.of(), ""),
                new AssetSupervisorImpl());

        assertFalse(result.success());
        assertTrue(result.error().contains("剧本里没有可抽取的人物和场景"));
        verify(gateway, never()).chat(anyLong(), any());
    }
}
