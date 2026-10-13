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
        // 角色画外音不归成旁白：下游要按说话人决定用谁的音色。
        assertTrue(system.contains("speaker 写「角色名（画外音）」"));
        assertTrue(system.contains("不要归成「旁白」"));
    }

    @Test
    void shotSchemaSerializesEmotionAndForbidsNarrationInCast() {
        String system = DramaTextPrompts.shotSystem();
        assertTrue(system.contains("局长（愤怒）：你给我站住！"));
        assertTrue(system.contains("有 emotion 时格式「说话人（情绪）：原文」"));
        assertTrue(system.contains("旁白一律「旁白：原文」"));
        assertTrue(system.contains("禁止列入旁白/画外音/解说"));
        // 画外音标记必须原样落到分镜 dialogue，编译层据此指定该角色的音色。
        assertTrue(system.contains("「说话人（画外音）：原文」"));
        assertTrue(system.contains("禁止改写成「旁白」"));
        assertTrue(system.contains("发声角色也应列入 character_names"));
    }

    @Test
    void shotSchemaPrefersLongerContinuousShotsAndKeepsDialogueTurnsTogether() {
        String system = DramaTextPrompts.shotSystem();

        assertTrue(system.contains("局长问责"));
        assertTrue(system.contains("duration_sec\": 12"));
        assertTrue(system.contains("优先估在 10–15 秒"));
        assertTrue(system.contains("同场连续对谈"));
        assertTrue(system.contains("不要一句一镜"));
        assertTrue(system.contains("有戏份的场常见 1～3 镜"));
        assertTrue(system.contains("不要仅因景别、运镜、正反打偏好而拆镜"));
        assertTrue(system.contains("对白、问责、争吵用中近景或过肩"));
        assertTrue(system.contains("全景或远景只用于一场里交代空间的开场镜头"));
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
        assertTrue(system.contains("每秒 4 字"),
                "拆镜必须给模型客观的口播折算，否则整场对谈塞进一个 15 秒镜、台词念一半就被切掉");
        assertTrue(system.contains("只数汉字等口播字，标点符号不发音、不计数"),
                "模型与服务端必须同口径：标点不算口播字，否则模型按含标点字数拆出的镜比服务端预算更碎");
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
        assertTrue(user.contains("每秒 4 字"),
                "user 提示词同样要带口播折算，双处强化模型按台词长度拆镜");
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

    @Test
    void shotSchemaAssignsActionsOnceAndVariesTitlesAndFraming() {
        String system = DramaTextPrompts.shotSystem();
        // 动作只归它发生的那一镜：整场动作链复述进每镜会连播成「拍桌四次、拔U盘四次」的循环感。
        assertTrue(system.contains("动作只归它发生的那一镜"),
                "短场拆多镜时每镜复述整场动作链，成片会像同一段戏反复重演");
        assertTrue(system.contains("禁止把整场动作链复述进每一镜"));
        assertTrue(system.contains("禁止后续镜头复演先前镜头已演过的动作"));
        assertTrue(system.contains("只演一次"));
        // 标题按节拍区分：同场各镜共用一个标题会让分镜列表失去导航意义。
        assertTrue(system.contains("title 概括本镜节拍而非场名"));
        assertTrue(system.contains("同一场内各镜标题不得雷同"));
        // 景别机位变化：相邻镜全写「中景」会让连续 15 秒镜看起来是同一画面。
        assertTrue(system.contains("相邻镜头的景别或机位必须有变化"));
        assertTrue(system.contains("禁止每镜都写同一个景别"));
        // 末拍景别：同场要接下镜时，末拍用中近景/特写，避免全景收尾导致链式起幅漂。
        assertTrue(system.contains("最后一拍 camera"));
        assertTrue(system.contains("优先用中景、近景或特写"));
        assertTrue(system.contains("不要用全景、远景或大幅度推拉摇移收尾"));

        String user = DramaTextPrompts.shotUser("{}", "洛白", 8, 30);
        assertTrue(user.contains("动作只归它发生的那一镜"),
                "user 提示词同步强化，与口播折算同款双处约束");
        assertTrue(user.contains("后续镜禁止复演先前镜已演过的动作"));
        assertTrue(user.contains("title 按节拍区分"));
        assertTrue(user.contains("相邻镜头景别或机位要有变化"));
        assertTrue(user.contains("最后一拍 camera 优先中景、近景或特写"));
    }

    @Test
    void enrichPromptVariesFramingAcrossAdjacentShots() {
        String system = DramaTextPrompts.shotEnrichSystem();
        assertTrue(system.contains("相邻分镜的景别或机位要有变化"),
                "最终景别由扩写层落笔，草稿全中景时扩写层必须主动换景别");
        assertTrue(system.contains("不要每镜都以「中景」开头"));
        assertTrue(system.contains("最后一拍"));
        assertTrue(system.contains("优先中景、近景或特写"));
        assertTrue(system.contains("不要用全景、远景或大幅度运镜收尾"));
    }

    @Test
    void enrichPromptLocksCastDialogueAndActions() {
        String system = DramaTextPrompts.shotEnrichSystem();
        assertTrue(system.contains("分镜视觉扩写层"));
        assertTrue(system.contains("人物不能多、不能少、不能换人"));
        assertTrue(system.contains("台词一个字不许动"));
        assertTrue(system.contains("谁做什么动作不许变"));
        assertTrue(system.contains("60～150 字"));
        assertTrue(system.contains("40～90 字"));
        assertTrue(system.contains("不要描写"));
        assertTrue(system.contains("narrative_goal"));
        assertTrue(system.contains("一个时空"));
        assertTrue(system.contains("改成"));
        assertTrue(system.contains("duration_sec"));
        assertTrue(system.contains("另一种时代"));

        String user = DramaTextPrompts.shotEnrichUser("[{\"chapter_index\":1}]", "{\"shots\":[]}");
        assertTrue(user.contains("场次上下文"));
        assertTrue(user.contains("待扩写分镜草稿"));
        assertTrue(user.contains("chapter_index 与 index 必须与输入一致"));
        assertTrue(user.contains("景别和运镜写进 camera"));
        assertFalse(user.contains("项目视觉风格"));

        String styled = DramaTextPrompts.shotEnrichUser("[{\"chapter_index\":1}]", "{\"shots\":[]}", "赛博朋克风。霓虹都市");
        assertTrue(styled.contains("项目视觉风格：赛博朋克风。霓虹都市"));
        assertTrue(styled.contains("不得引入另一种时代的陈设"));
    }
}
