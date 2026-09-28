package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextPromptsTest {

    @Test
    void scriptSchemaRequiresEmotionClosedSetAndNarrationRules() {
        String system = DramaTextPrompts.scriptSystem();
        assertTrue(system.contains("\"emotion\": \"愤怒\""));
        assertTrue(system.contains("characters 只收录本场真实出场人物"));
        assertTrue(system.contains("旁白/画外音/解说/VO/OS"));
        assertTrue(system.contains("dialogue.emotion 闭集"));
        assertTrue(system.contains("旁白句 emotion 一律空字符串"));
    }

    @Test
    void shotSchemaSerializesEmotionAndForbidsNarrationInCast() {
        String system = DramaTextPrompts.shotSystem();
        assertTrue(system.contains("局长（愤怒）：你给我站住！"));
        assertTrue(system.contains("有 emotion 时格式「说话人（情绪）：原文」"));
        assertTrue(system.contains("旁白一律「旁白：原文」"));
        assertTrue(system.contains("禁止列入旁白/画外音/解说"));
    }

    @Test
    void shotSchemaPrefersLongerContinuousShotsAndKeepsDialogueTurnsTogether() {
        String system = DramaTextPrompts.shotSystem();

        assertTrue(system.contains("侦探社对谈"));
        assertTrue(system.contains("duration_sec\": 12"));
        assertTrue(system.contains("优先估在 10–15 秒"));
        assertTrue(system.contains("同场连续对谈"));
        assertTrue(system.contains("不要一句一镜"));
        assertTrue(system.contains("有戏份的场常见 1～3 镜"));
        assertTrue(system.contains("不要仅因景别、运镜、正反打偏好而拆镜"));
        assertTrue(system.contains("下列硬边界才拆开"));
        assertTrue(system.contains("按动作/对白里出场先后排序"));
        assertTrue(system.contains("画面出入点"));
        assertTrue(system.contains("相对站位要保持稳定"));
        assertTrue(system.contains("禁止无理由地「忽左忽右」"));
        assertTrue(system.contains("默认仍在左侧"));
        assertTrue(system.contains("不要写 compiled_prompt"));
        assertTrue(system.contains("dialogue（台词）优先完整保留"));
        assertTrue(system.contains("禁止摘要、改写、删半句"));
        assertTrue(system.contains("dialogue 不受上述字数上限约束"));
        assertFalse(system.contains("有戏份的场通常 2～5 镜"));
        assertFalse(system.contains("对白轮次明显切换"));
        assertFalse(system.contains("全部镜头必须等于用户指定的秒数"));
        assertFalse(system.contains("交代空间与二人关系"));
    }

    @Test
    void shotUserAsksForTenToFifteenPreferenceWithoutForcingEpisodeSum() {
        String user = DramaTextPrompts.shotUser("{}", "洛白", 8, 30);
        assertTrue(user.contains("优先估在 10 到 15 秒"));
        assertTrue(user.contains("无法判断时再用 10 秒"));
        assertTrue(user.contains("不要所有镜头填同一个秒数"));
        assertTrue(user.contains("严格按剧本场次先后"));
        assertTrue(user.contains("本集故事体量大约 30 秒"));
        assertTrue(user.contains("镜头粒度优先少拆"));
        assertTrue(user.contains("同场连续对谈"));
        assertTrue(user.contains("有戏份的单场常见 1～3 镜"));
        assertTrue(user.contains("台词必须尽量原样保留"));
        assertTrue(user.contains("不得为压缩体积而丢掉或改写台词"));
        assertFalse(user.contains("不要压成少数超长镜"));
        assertFalse(user.contains("对白轮次切换"));
        assertFalse(user.contains("有戏份的单场通常 2～5 镜"));
        assertFalse(user.contains("各镜秒数之和尽量接近"));
        assertFalse(user.contains("必须是 8，不得改成其它秒数"));
    }

    @Test
    void preferredFallbackBiasesTowardTenToFifteen() {
        assertEquals(10, DramaTextPrompts.preferredFallback(5));
        assertEquals(10, DramaTextPrompts.preferredFallback(8));
        assertEquals(12, DramaTextPrompts.preferredFallback(12));
        assertEquals(15, DramaTextPrompts.preferredFallback(20));
    }

    @Test
    void chunkPromptsMarkFragmentBounds() {
        String script = DramaTextPrompts.scriptUserChunk("原文", 2, 3);
        assertTrue(script.contains("第 2/3 片段"));
        assertTrue(script.contains("服务端会重编号"));

        String shot = DramaTextPrompts.shotUserChunk("{}", "洛白", 8, null, 1, 2);
        assertTrue(shot.contains("第 1/2 片段"));
        assertTrue(shot.contains("服务端会映射回全集场次号"));
        assertTrue(shot.contains("shots 必须是非空数组"));
        assertTrue(shot.contains("禁止输出思考过程"));
    }

    @Test
    void shotSchemaForbidsReorderingToFitDuration() {
        String system = DramaTextPrompts.shotSystem();
        assertTrue(system.contains("禁止为了凑总秒数而删戏或打乱时间线"));
        assertTrue(system.contains("不同场景不得并进一镜"));
        assertTrue(system.contains("必须按 scenes 的先后"));
    }

    @Test
    void repairPromptHasAWholeRequestLengthBudget() {
        String repair = DramaTextPrompts.repairUser(
                "剧本".repeat(100_000), "ScriptDoc", "JSON 截断", "x".repeat(100_000));

        assertTrue(repair.length() <= 25_000,
                "修复请求不能比首次请求更无上限，否则容易再次超出模型上下文");
    }
}
