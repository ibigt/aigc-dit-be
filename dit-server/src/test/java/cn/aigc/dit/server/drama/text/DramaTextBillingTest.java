package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DramaTextBillingTest {

    @Test
    void billsOnlyContractAcceptedRetryAtBusinessCompletion() {
        BillingGateway gateway = new BillingGateway();
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ScriptDoc result = service.parseScript(21L, "一段剧本");

        assertNotNull(result);
        assertEquals(2, gateway.calls);
        assertNotNull(gateway.settled);
        assertEquals(120, gateway.settled.getPromptTokens());
        assertEquals(80, gateway.settled.getCompletionTokens());
        assertEquals(20, gateway.settled.getCachedPromptTokens());
        assertEquals(1, gateway.settled.getAcceptedResponses());
    }

    private static final class BillingGateway implements TextChatGateway {
        private int calls;
        private TextBillingSession settled;

        @Override
        public boolean llmAvailable(long seriesId) {
            return true;
        }

        @Override
        public Optional<ResolvedTextModel> resolve(long seriesId) {
            return Optional.of(new ResolvedTextModel(11L, "m", "openai-compat", "custom",
                    "https://example.com/v1", "m"));
        }

        @Override
        public TextChatResult chat(long seriesId, TextChatRequest req) {
            calls++;
            if (calls == 1) {
                return TextChatResult.of("不是 JSON", "m", "stop", 9999, 9999, 500);
            }
            return TextChatResult.of("""
                    {"title":"测试","logline":"故事","stylePrefix":"写实", "scenes":[{
                      "index":1,"heading":"室内","location":"房间","time":"日","interior":true,
                      "summary":"人物出场","characters":["甲"],"props":[]
                    }]}
                    """, "m", "stop", 120, 80, 20);
        }

        @Override
        public TextBillingSession openBilling(long seriesId, String operation) {
            return new TextBillingSession(7L, seriesId, 11L, operation,
                    Map.of("unit", "千Token", "sale", Map.of("input", 1, "output", 1, "cache", 1)));
        }

        @Override
        public void settleBilling(TextBillingSession session) {
            settled = session;
        }
    }
}
