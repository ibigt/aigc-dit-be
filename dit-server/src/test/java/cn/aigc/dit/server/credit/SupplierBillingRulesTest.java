package cn.aigc.dit.server.credit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplierBillingRulesTest {

    @Test
    void miniMaxAdapterUsesTwoCharactersPerHanzi() {
        SupplierBillingRules.CharacterRule rule =
                SupplierBillingRules.resolveCharacterRule("minimax-speech", "官方API", "speech-2.8-hd");
        assertEquals(2, rule.weight());
        assertEquals("1 汉字 = 2 字符", rule.label());
        assertTrue(rule.source().contains("MiniMax"), "口径说明应标注官方依据");
    }

    @Test
    void openAiCompatibleSpeechUsesOneCharacterPerHanzi() {
        SupplierBillingRules.CharacterRule rule =
                SupplierBillingRules.resolveCharacterRule("openai-speech", "自定义配置", "gpt-4o-mini-tts");
        assertEquals(1, rule.weight());
    }

    @Test
    void fallsBackToProviderThenModelKey() {
        // 适配协议未登记，但供应商能判定
        assertEquals(1, SupplierBillingRules.resolveCharacterRule("custom-tts", "火山引擎", "x").weight());
        assertEquals(2, SupplierBillingRules.resolveCharacterRule("custom-tts", "minimax", "x").weight());
        // 适配协议与供应商都未登记，用模型名兜底
        assertEquals(2, SupplierBillingRules.resolveCharacterRule("custom-tts", "自定义", "speech-2.8-hd").weight());
        assertEquals(1, SupplierBillingRules.resolveCharacterRule("custom-tts", "自定义", "doubao-seed-tts").weight());
    }

    @Test
    void unknownSupplierFallsBackToDefault() {
        assertEquals(SupplierBillingRules.DEFAULT_CHARACTER.weight(),
                SupplierBillingRules.resolveCharacterRule("whatever", "nobody", "nothing").weight());
    }

    @Test
    void modelLevelOverrideWinsOverBuiltInRule() {
        // 中转渠道等特殊情况：模型定价里显式配了 cjkWeight=1，覆盖内置的 MiniMax 口径
        Map<String, Object> pricing = Map.of("cjkWeight", 1);
        assertEquals(1, CreditCalculator.cjkWeight(pricing, "minimax-speech", "官方API", "speech-2.8-hd"));
        // 未配置时用内置表
        assertEquals(2, CreditCalculator.cjkWeight(Map.of(), "minimax-speech", "官方API", "speech-2.8-hd"));
    }

    @Test
    void characterRuleReportsEffectiveLabelAndSource() {
        SupplierBillingRules.CharacterRule builtin =
                CreditCalculator.characterRule(Map.of(), "minimax-speech", "官方API", "speech-2.8-hd");
        assertEquals(2, builtin.weight());
        assertEquals("1 汉字 = 2 字符", builtin.label());
        assertTrue(builtin.source().contains("MiniMax"));

        // 覆盖时说明与内置规则的差异
        SupplierBillingRules.CharacterRule overridden =
                CreditCalculator.characterRule(Map.of("cjkWeight", 1), "minimax-speech", "官方API", "speech-2.8-hd");
        assertEquals(1, overridden.weight());
        assertEquals("1 字 = 1 字符", overridden.label());
        assertTrue(overridden.source().contains("覆盖"));
    }
}
