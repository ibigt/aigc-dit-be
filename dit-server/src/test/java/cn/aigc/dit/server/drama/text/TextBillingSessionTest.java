package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBillingSessionTest {

    @Test
    void aggregatesAcceptedResponsesAndCalculatesOnce() {
        TextBillingSession session = new TextBillingSession(7L, 21L, 11L, "SCRIPT_PARSE", Map.of(
                "unit", "千Token",
                "sale", Map.of("input", 1, "output", 2, "cache", 0.2)
        ));

        session.record(TextChatResult.of("a", "m", "stop", 1000, 500, 200));
        session.record(TextChatResult.of("b", "m", "stop", 500, 500, 100));

        assertTrue(session.hasUsage());
        assertEquals(1500, session.getPromptTokens());
        assertEquals(1000, session.getCompletionTokens());
        assertEquals(300, session.getCachedPromptTokens());
        assertEquals(2, session.getAcceptedResponses());
        assertEquals(4, session.creditCost());
    }
}
