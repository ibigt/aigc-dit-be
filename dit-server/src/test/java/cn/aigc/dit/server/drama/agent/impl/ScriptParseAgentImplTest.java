package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.ScriptParseReq;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptParseAgentImplTest {

    private final TextChatGateway gateway = mock(TextChatGateway.class);
    private final ScriptPromptResolver promptResolver = mock(ScriptPromptResolver.class);
    private final ScriptParseAgentImpl agent = new ScriptParseAgentImpl(gateway, promptResolver, new ObjectMapper());
    private final ScriptSupervisorImpl supervisor = new ScriptSupervisorImpl();

    @Test
    void repairThatDropsCharactersKeepsThePreviousScript() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        String usable = """
                {"scenes":[{"index":1,"heading":"灯塔-夜","location":"灯塔","characters":["洛白"],
                  "props":["怀表"],"dialogue":[{"speaker":"洛白","line":"我们走吧现在"}]}]}
                """;
        String empty = """
                {"scenes":[{"index":1,"heading":"空","location":"","characters":[],"props":[],"dialogue":[]}]}
                """;
        AtomicInteger calls = new AtomicInteger();
        when(gateway.chat(anyLong(), any())).thenAnswer(invocation ->
                TextChatResult.of(calls.getAndIncrement() == 0 ? usable : empty, "deepseek-chat", "stop"));

        AgentResult<ScriptDoc> result = agent.executeWithSupervision(context(), request(), supervisor);

        assertTrue(result.success());
        assertTrue(result.supervisorRepaired());
        assertEquals(List.of("洛白"), result.output().getScenes().get(0).getCharacters());
        assertEquals("灯塔", result.output().getScenes().get(0).getLocation());
    }

    @Test
    void repairThatPassesValidationReplacesThePreviousScript() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        String missing = """
                {"scenes":[{"index":1,"heading":"灯塔-夜","location":"灯塔","characters":["洛白"],
                  "props":["怀表"],"dialogue":[{"speaker":"洛白","line":"我们走吧现在"}]}]}
                """;
        String fixed = """
                {"scenes":[{"index":1,"heading":"灯塔-夜","location":"灯塔","characters":["洛白","徐明远"],
                  "props":["怀表"],"dialogue":[{"speaker":"洛白","line":"我们走吧现在"}]}]}
                """;
        AtomicInteger calls = new AtomicInteger();
        when(gateway.chat(anyLong(), any())).thenAnswer(invocation ->
                TextChatResult.of(calls.getAndIncrement() == 0 ? missing : fixed, "deepseek-chat", "stop"));

        AgentResult<ScriptDoc> result = agent.executeWithSupervision(context(), request(), supervisor);

        assertTrue(result.success());
        assertEquals(List.of("洛白", "徐明远"), result.output().getScenes().get(0).getCharacters());
    }

    private static AgentContext context() {
        return new AgentContext(1L, 225L, "session", "", "deepseek-chat");
    }

    private static ScriptParseReq request() {
        SkeletonDoc skeleton = new SkeletonDoc(
                "梗概", "",
                List.of(new SkeletonDoc.EpisodeOutline(
                        1, "第一集", "开篇", "钩子", List.of("洛白"),
                        List.of(new SkeletonDoc.SceneHint("灯塔", "主场景")),
                        "高潮", "下集")),
                List.of("徐明远"),
                List.of());
        return new ScriptParseReq(1L, "我们走吧现在", skeleton, "");
    }
}
