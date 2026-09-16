package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextPromptsTest {

    @Test
    void shotSchemaAsksForVisibleActionNotPlotBeats() {
        String system = DramaTextPrompts.shotSystem();

        assertTrue(system.contains("全景。风扇转动，夏安往白板贴照片，洛白用书盖脸。"));
        assertTrue(system.contains("按动作/对白里出场先后排序"));
        assertTrue(system.contains("动作只写一个人时"));
        assertTrue(system.contains("只列本镜被使用或被拿在手上的道具"));
        assertTrue(system.contains("画面出入点"));
        assertTrue(system.contains("2～3 个连续可见动作"));
        assertTrue(system.contains("不要写 compiled_prompt"));
        assertTrue(system.contains("按本镜对白长短、动作节拍"));
        assertTrue(system.contains("同一场允许只有 1 镜"));
        assertTrue(system.contains("15 秒内优先合并"));
        assertTrue(system.contains("不要仅因景别变化或运镜变化拆镜"));
        assertFalse(system.contains("一场至少 2 镜"));
        assertFalse(system.contains("不要压成少数超长镜"));
        assertFalse(system.contains("全部镜头必须等于用户指定的秒数"));
        assertFalse(system.contains("交代空间与二人关系"));
        assertFalse(system.contains("不要写镜头语言"));
        assertFalse(system.contains("门被推开"));
    }

    @Test
    void shotUserAsksForPerShotDurationWithoutForcingEpisodeSum() {
        String user = DramaTextPrompts.shotUser("{}", "洛白", 8, 30);
        assertTrue(user.contains("自行估计"));
        assertTrue(user.contains("不要所有镜头填同一个秒数"));
        assertTrue(user.contains("严格按剧本场次先后"));
        assertTrue(user.contains("本集故事体量大约 30 秒"));
        assertTrue(user.contains("镜头数量以最少且完整为目标"));
        assertTrue(user.contains("15 秒内优先合并成一镜"));
        assertFalse(user.contains("大约 6 个只是参考"));
        assertFalse(user.contains("各镜秒数之和尽量接近"));
        assertFalse(user.contains("必须是 8，不得改成其它秒数"));
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
