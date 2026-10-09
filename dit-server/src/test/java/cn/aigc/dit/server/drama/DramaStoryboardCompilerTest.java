package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaStoryboardCompilerTest {

    @Test
    void compilesStylePrefixPictureOrderAndCastConstraint() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相；黑碎盖；修长；立领长风衣轮廓；锐利眼神", "完整人物描述永不缩写"),
                ref(2, "夏安", DramaContract.ASSET_TYPE_CHARACTER, "夏安身份不变量", "夏安完整描述"),
                ref(3, "古铜怀表", DramaContract.ASSET_TYPE_PROP, "古铜怀表标志物", "古铜怀表完整描述"),
                ref(4, "幻梦侦探社", DramaContract.ASSET_TYPE_SCENE, "侦探社空间锁", "日戏办公室"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏，风扇与白板红线",
                "交代空间与二人关系，局长闯入",
                "局长：洛老弟，别睡了。",
                "开场",
                "洛白坐起掏表",
                5,
                List.of("洛白", "夏安", "局长"),
                3,
                List.of(new DramaStoryboardCompiler.NamedAppearance("局长", "中年男性；浓眉；灰西装")));

        DramaStoryboardCompiler.Result result = DramaStoryboardCompiler.compile(prefix, refs, shot);
        String prompt = result.prompt();

        assertTrue(prompt.startsWith("<Picture 1>"));
        assertTrue(prompt.contains(DramaStyles.withVideoSceneClause(prefix)));
        assertTrue(prompt.contains("<Picture 1> 洛白"));
        assertTrue(prompt.contains("<Picture 2> 夏安"));
        assertTrue(prompt.contains("<Picture 3> 古铜怀表"));
        assertTrue(prompt.contains("<Picture 4> 幻梦侦探社"));
        int p1 = prompt.indexOf("<Picture 1>");
        int p2 = prompt.indexOf("<Picture 2>");
        int p3 = prompt.indexOf("<Picture 3>");
        int p4 = prompt.indexOf("<Picture 4>");
        assertTrue(p1 < p2 && p2 < p3 && p3 < p4);
        assertTrue(prompt.contains("青年男性；清冷骨相；黑碎盖；修长；立领长风衣轮廓；锐利眼神"));
        assertTrue(prompt.contains("只借洛白的身份与当前服装，不继承姿态、背景、构图、机位、光线"));
        assertTrue(prompt.contains("必须在戏里被使用"));
        assertTrue(prompt.contains("必须有人在此演戏"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.MOVIE_SCENE_MUST));
        assertFalse(prompt.contains("定妆"));
        assertFalse(prompt.contains("Identity lock"));
        assertFalse(prompt.contains("真实人脸"));
        assertFalse(prompt.contains("真人照片"));
        assertFalse(prompt.contains("real people"));
        assertTrue(prompt.contains("本镜恰好 3 人，不得增删角色"));
        assertTrue(prompt.contains("出场：洛白=<Picture 1>、夏安=<Picture 2>。"));
        assertTrue(prompt.contains("未出图角色按外貌出演：中年男性；浓眉；灰西装。"));
        assertFalse(prompt.contains("出场：洛白、夏安、局长"));
        assertTrue(prompt.contains("参考图：<Picture 1>、<Picture 2> 为角色参考图；<Picture 3> 为道具参考图；<Picture 4> 为场景参考图。"));
        assertTrue(prompt.contains("Duration: 5 seconds"));
        assertTrue(prompt.contains("对白执行："));
        assertTrue(prompt.contains("只念台词正文，不念角色名和冒号"));
        assertTrue(prompt.contains("角色“局长”逐字说出：“洛老弟，别睡了。”"));
        assertFalse(prompt.contains("完整人物描述永不缩写"));
        assertFalse(prompt.contains("三视图"));
        assertFalse(prompt.contains("不要剧情场面"));
        assertFalse(prompt.contains("画面中不要出现人物"));
        assertFalse(prompt.contains("浅灰或纯白"));
        assertFalse(prompt.contains("…"));
        assertTrue(prompt.indexOf("<Picture") >= 0);
    }

    @Test
    void actionBindsActorNameToMatchingPicture() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"),
                ref(2, "夏安", DramaContract.ASSET_TYPE_CHARACTER, "夏安身份", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "特写。洛白在桌后用书盖脸装睡。",
                "",
                "书盖住脸",
                "洛白仍闭眼",
                8,
                List.of("洛白"),
                1);
        String prompt = DramaStoryboardCompiler.compile(prefix, refs, shot).prompt();
        assertTrue(prompt.contains("Action: 特写。洛白（<Picture 1>）在桌后用书盖脸装睡。"));
        assertFalse(prompt.contains("Camera:"));
        assertFalse(prompt.contains("Ambiance:"));
        assertTrue(prompt.contains("出场：洛白=<Picture 1>。"));
        assertFalse(prompt.contains("出场：夏安"));
        assertFalse(prompt.contains("Identity lock"));
    }

    @Test
    void splitsCameraActionAndAmbiance() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "百叶窗日光斜切进来，浮尘漂浮",
                "洛白双手把书压在脸上，胸腔随呼吸轻轻起伏。",
                "",
                "",
                "",
                5,
                List.of("洛白"),
                1,
                List.of(),
                "特写，静止",
                "风扇低鸣，纸页轻响");
        String prompt = DramaStoryboardCompiler.compile(prefix, refs, shot).prompt();
        assertTrue(prompt.contains("Camera: 特写，静止"));
        assertTrue(prompt.contains("Action: 洛白（<Picture 1>）双手把书压在脸上，胸腔随呼吸轻轻起伏。"));
        assertTrue(prompt.contains("Ambiance: 风扇低鸣，纸页轻响"));
        assertTrue(prompt.contains("Scene: 百叶窗日光斜切进来，浮尘漂浮"));
        assertTrue(prompt.indexOf("Scene:") < prompt.indexOf("Camera:"));
        assertTrue(prompt.indexOf("Camera:") < prompt.indexOf("Action:"));
        assertTrue(prompt.indexOf("Action:") < prompt.indexOf("Ambiance:"));
    }

    @Test
    void fullIdentityKeepsOnlyIdentityInvariants() {
        DramaStoryboardCompiler.ActiveReference ref = ref(
                1, "洛白", DramaContract.ASSET_TYPE_CHARACTER,
                "青年男性；清冷骨相",
                "浅灰或纯白背景的角色三视图，不要剧情场面，画面中不要出现人物");
        assertEquals("青年男性；清冷骨相", DramaStoryboardCompiler.fullIdentity(ref));
        assertFalse(DramaStoryboardCompiler.fullIdentity(ref).contains("三视图"));
    }

    @Test
    void captionLinesStripSpeakerNamesAndSkipEmpty() {
        assertEquals(List.of("洛老弟，别睡了。"),
                DramaStoryboardCompiler.captionLines("局长：洛老弟，别睡了。"));
        assertEquals(List.of("风很大。", "走吧。"),
                DramaStoryboardCompiler.captionLines("旁白：风很大。\n洛白：走吧。"));
        assertEquals(List.of("你给我站住！"),
                DramaStoryboardCompiler.captionLines("局长（愤怒）：你给我站住！"));
        assertEquals(List.of("没有冒号的台词"),
                DramaStoryboardCompiler.captionLines("没有冒号的台词"));
        assertEquals(List.of(), DramaStoryboardCompiler.captionLines("局长：  \n  "));
        assertEquals(List.of(), DramaStoryboardCompiler.captionLines(null));
    }

    @Test
    void omitsSpokenDialogueWhenNoLines() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "场景", "动作", null, "in", "out", 5, List.of("洛白"), 1);
        String prompt = DramaStoryboardCompiler.compile(prefix, List.of(ref(
                1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "完整人物描述永不缩写")), shot).prompt();
        assertFalse(prompt.contains("对白执行"));
        assertTrue(prompt.contains("出场：洛白=<Picture 1>。"));
        assertFalse(prompt.contains("完整人物描述永不缩写"));
    }

    @Test
    void fingerprintChangesWhenIdentityVersionChanges() {
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "场景", "动作", null, "in", "out", 5, List.of("洛白"), 1);
        String prefix = "二次元漫画风";
        String v1 = DramaStoryboardCompiler.fingerprint(prefix, List.of(ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "d", "i")), shot);
        String v2 = DramaStoryboardCompiler.fingerprint(
                prefix,
                List.of(new DramaStoryboardCompiler.ActiveReference(1, 2, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "d", "i")),
                shot);
        assertNotEquals(v1, v2);
        assertEquals(64, v1.length());
    }

    @Test
    void i2vOmitsPicturesAndAddsFirstFrameStart() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "洛白用书盖脸。",
                "",
                "开场",
                "洛白仍闭眼",
                5,
                List.of("洛白"),
                1);
        String prompt = DramaStoryboardCompiler.compile(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false)).prompt();
        assertFalse(prompt.contains("<Picture"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertTrue(prompt.contains(DramaStoryboardCompiler.I2V_IDENTITIES_HEADER));
        assertTrue(prompt.contains("洛白：青年男性；清冷骨相"));
        assertTrue(prompt.contains("Action: 洛白用书盖脸。"));
        assertFalse(prompt.contains("洛白（<Picture 1>）"));
        String r2v = DramaStoryboardCompiler.fingerprint(prefix, refs, shot);
        String i2v = DramaStoryboardCompiler.fingerprint(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false));
        assertNotEquals(r2v, i2v);
    }

    @Test
    void i2vKeepsIncomingCharacterIdentityWithoutPictures() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "中景。局长推门将档案摔桌，洛白起身。",
                "",
                "洛白用书盖脸",
                "局长站在桌前",
                5,
                List.of("局长", "洛白"),
                2,
                List.of(new DramaStoryboardCompiler.NamedAppearance("局长", "中年男性；浓眉；灰西装")));
        String prompt = DramaStoryboardCompiler.compile(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false)).prompt();
        assertFalse(prompt.contains("<Picture"));
        assertTrue(prompt.contains("局长：中年男性；浓眉；灰西装"));
        assertTrue(prompt.contains("洛白：青年男性；清冷骨相"));
        assertTrue(prompt.contains("禁止用首帧里已有的人顶替新角色"));
        assertTrue(prompt.contains("Action: 中景。局长推门将档案摔桌，洛白起身。"));
    }

    @Test
    void i2vReplacesGenericLockPhraseWithDistinctLook() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "洛白的外貌、发型、服装和年龄必须保持一致", "设定图"),
                ref(2, "局长", DramaContract.ASSET_TYPE_CHARACTER, "局长的外貌、发型、服装和年龄必须保持一致", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "中景。局长推门将档案摔桌，洛白起身。",
                "",
                "门被推开",
                "厚档案落在桌面",
                5,
                List.of("局长", "洛白"),
                2);
        String prompt = DramaStoryboardCompiler.compile(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false)).prompt();
        assertFalse(prompt.contains("局长的外貌、发型、服装和年龄必须保持一致"));
        assertTrue(prompt.contains("局长：角色“局长”，" + DramaStoryboardCompiler.I2V_IDENTITY_FALLBACK));
        assertTrue(prompt.contains("洛白：角色“洛白”，" + DramaStoryboardCompiler.I2V_IDENTITY_FALLBACK));
        assertTrue(prompt.contains("禁止把首帧已有人物的脸改贴过来"));
    }

    @Test
    void i2vAndR2vCompiledPromptsStayIsolated() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"),
                ref(2, "局长", DramaContract.ASSET_TYPE_CHARACTER, "中年男性；浓眉；灰西装", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "中景。局长推门将档案摔桌，洛白起身。",
                "",
                "开场",
                "局长站在桌前",
                5,
                List.of("局长", "洛白"),
                2);
        String r2v = DramaStoryboardCompiler.compile(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.r2v()).prompt();
        String i2v = DramaStoryboardCompiler.compile(
                prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false)).prompt();

        assertTrue(r2v.contains("<Picture 1>"));
        assertTrue(r2v.contains("洛白（<Picture 1>）") || r2v.contains("局长=<Picture 2>") || r2v.contains("<Picture 2> 局长"));
        assertFalse(r2v.contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertFalse(r2v.contains(DramaStoryboardCompiler.I2V_IDENTITIES_HEADER));
        assertFalse(r2v.contains("禁止用首帧里已有的人顶替新角色"));

        assertFalse(i2v.contains("<Picture"));
        assertFalse(i2v.contains("参考图："));
        assertTrue(i2v.contains(DramaStoryboardCompiler.I2V_MUST_START));
        assertTrue(i2v.contains(DramaStoryboardCompiler.I2V_IDENTITIES_HEADER));
        assertTrue(i2v.contains("局长：中年男性；浓眉；灰西装"));
        assertNotEquals(
                DramaStoryboardCompiler.fingerprint(prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.r2v()),
                DramaStoryboardCompiler.fingerprint(prefix, refs, List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("i2v", false)));
    }

    @Test
    void stillKeepsPicturesAndLocksOpeningPose() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "全景。洛白用书盖脸趴在红木桌。",
                "",
                "开场",
                null,
                5,
                List.of("洛白"),
                1);
        String prompt = DramaStoryboardCompiler.compileStill(prefix, refs, shot).prompt();
        assertTrue(prompt.contains("<Picture 1>"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.STILL_MUST));
        assertTrue(prompt.contains("洛白（<Picture 1>）用书盖脸"));
        assertFalse(prompt.contains("Duration:"));
        assertFalse(prompt.contains(DramaStoryboardCompiler.I2V_MUST_START));
    }

    @Test
    void stillWithPrevTailKeepsActionComposition() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"),
                DramaStoryboardCompiler.prevTailStillReference());
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "特写。洛白手中古铜怀表。",
                "",
                "开场",
                null,
                5,
                List.of("洛白"),
                1);
        String prompt = DramaStoryboardCompiler.compileStill(prefix, refs, shot).prompt();
        assertTrue(prompt.contains("<Picture 1>"));
        assertTrue(prompt.contains("<Picture 2>"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.PREV_TAIL_NAME));
        assertTrue(prompt.contains(DramaStoryboardCompiler.STILL_PREV_TAIL_HINT));
        assertFalse(prompt.contains(DramaStoryboardCompiler.PREV_TAIL_HINT));
        assertTrue(prompt.contains("特写"));
    }

    @Test
    void r2vChainAddsContinuityHintWithoutChangingDefaultFingerprintUnlessEnabled() {
        String prefix = "二次元漫画风";
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "场景", "动作", null, "in", "out", 5, List.of("洛白"), 1);
        String plain = DramaStoryboardCompiler.compile(prefix, List.of(), shot).prompt();
        String chained = DramaStoryboardCompiler.compile(
                prefix, List.of(), List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("r2v", true)).prompt();
        assertFalse(plain.contains(DramaStoryboardCompiler.R2V_CHAIN_HINT));
        assertTrue(chained.contains(DramaStoryboardCompiler.R2V_CHAIN_HINT));
        assertEquals(
                DramaStoryboardCompiler.fingerprint(prefix, List.of(), shot),
                DramaStoryboardCompiler.fingerprint(prefix, List.of(), List.of(), shot, DramaStoryboardCompiler.CompileOptions.r2v()));
        assertNotEquals(
                DramaStoryboardCompiler.fingerprint(prefix, List.of(), shot),
                DramaStoryboardCompiler.fingerprint(
                        prefix, List.of(), List.of(), shot, DramaStoryboardCompiler.CompileOptions.of("r2v", true)));
    }

    @Test
    void compilesVoiceIdentityAndIncludesItInFingerprint() {
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "雨夜", "回头", "洛白：别跟过来。", null, null, 5, List.of("洛白"), 1);
        var audio = new DramaStoryboardCompiler.AudioReference(
                "洛白", "低沉、克制的青年男声", "users/1/drama/voices/luobai.wav");
        DramaStoryboardCompiler.Result result = DramaStoryboardCompiler.compile(
                "电影写实", List.of(), List.of(audio), shot);
        assertTrue(result.prompt().contains("<Audio 1>"));
        assertTrue(result.prompt().contains("低沉、克制的青年男声"));
        String changed = DramaStoryboardCompiler.fingerprint(
                "电影写实", List.of(), List.of(new DramaStoryboardCompiler.AudioReference(
                        "洛白", "清亮男声", "users/1/drama/voices/luobai.wav")), shot);
        assertNotEquals(result.fingerprint(), changed);
    }

    @Test
    void compilesTextOnlyVoiceWithoutAudioTag() {
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "室内", "说话", "洛白：知道了。", null, null, 5, List.of("洛白"), 1);
        DramaStoryboardCompiler.Result result = DramaStoryboardCompiler.compile(
                "电影写实", List.of(), List.of(new DramaStoryboardCompiler.AudioReference(
                        "洛白", "低沉克制的青年男声", null)), shot);
        assertTrue(result.prompt().contains("低沉克制的青年男声"));
        assertFalse(result.prompt().contains("<Audio 1>"));
    }

    @Test
    void compilesBeatSequenceWithoutExactCastOrRestrainedPerformance() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "局长", DramaContract.ASSET_TYPE_CHARACTER, "中年男性；浓眉；灰西装", "设定图"),
                ref(2, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性；清冷骨相", "设定图"),
                ref(3, "档案", DramaContract.ASSET_TYPE_PROP, "牛皮档案", "档案"));
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("局长在门口，洛白在画面左侧，档案在桌上。");
        DramaShotBeats.Beat enter = beat("中景，跟拍", "局长推门进入。", "门开", null);
        DramaShotBeats.Beat slam = beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", null);
        DramaShotBeats.Beat line = beat("特写，静止", "局长盯着洛白。", "接档案落桌", "局长（愤怒）：你给我站住！");
        beats.setBeats(List.of(enter, slam, line));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "局长办公室日戏",
                "局长进门摔档案并问责。",
                "局长（愤怒）：你给我站住！",
                "门开",
                "档案落在桌上",
                12,
                List.of("局长", "洛白"),
                2,
                List.of(),
                null,
                "纸页拍在桌面",
                beats);

        String prompt = DramaStoryboardCompiler.compile(prefix, refs, shot).prompt();

        int first = prompt.indexOf("镜头1:");
        int second = prompt.indexOf("镜头2:");
        int third = prompt.indexOf("镜头3:");
        assertTrue(first >= 0 && first < second && second < third);
        assertTrue(prompt.contains("站位: 局长（<Picture 1>）在门口，洛白（<Picture 2>）在画面左侧，档案（<Picture 3>）在桌上。"));
        assertTrue(prompt.substring(first, second).contains("局长（<Picture 1>）推门进入。"));
        assertTrue(prompt.substring(second, third).contains("档案（<Picture 3>）"));
        assertTrue(prompt.substring(third, prompt.indexOf("Continuity in:")).contains("你给我站住！"));
        assertFalse(prompt.contains("Dialogue:"));
        assertFalse(prompt.contains("Action:"));
        assertFalse(prompt.contains("Camera:"));
        assertFalse(prompt.contains("本镜恰好"));
        assertTrue(prompt.contains("本镜出场人物为局长=<Picture 1>、洛白=<Picture 2>。"));
        assertTrue(prompt.contains("某一镜头没点名的人保持起始站位或留在画外，禁止突然入画"));
        assertFalse(prompt.contains("动作自然克制"));
        assertTrue(prompt.contains("神态与口型"));

        DramaStoryboardCompiler.ShotContent withoutBeats = new DramaStoryboardCompiler.ShotContent(
                "局长办公室日戏",
                "局长进门摔档案并问责。",
                "局长（愤怒）：你给我站住！",
                "门开",
                "档案落在桌上",
                12,
                List.of("局长", "洛白"),
                2,
                List.of(),
                null,
                "纸页拍在桌面");
        assertNotEquals(
                DramaStoryboardCompiler.fingerprint(prefix, refs, shot),
                DramaStoryboardCompiler.fingerprint(prefix, refs, withoutBeats));
        DramaShotBeats changed = new DramaShotBeats();
        changed.setBlocking(beats.getBlocking());
        changed.setBeats(List.of(enter, beat("近景，下摇", "局长把档案轻轻放下。", "接推门进入", null), line));
        DramaStoryboardCompiler.ShotContent changedShot = new DramaStoryboardCompiler.ShotContent(
                shot.scene(), shot.narrativeGoal(), shot.dialogue(), shot.continuityIn(), shot.continuityOut(),
                shot.durationSec(), shot.characterNames(), shot.castCount(), shot.unnamedAppearances(),
                shot.camera(), shot.ambiance(), changed);
        assertNotEquals(
                DramaStoryboardCompiler.fingerprint(prefix, refs, shot),
                DramaStoryboardCompiler.fingerprint(prefix, refs, changedShot));
    }

    private static DramaShotBeats.Beat beat(String camera, String action, String continuity, String dialogue) {
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera(camera);
        beat.setAction(action);
        beat.setContinuity(continuity);
        beat.setDialogue(dialogue);
        return beat;
    }

    private static DramaStoryboardCompiler.ActiveReference ref(
            long id, String name, String type, String identity, String descriptor) {
        return new DramaStoryboardCompiler.ActiveReference(id, 1, name, type, descriptor, identity);
    }
}
