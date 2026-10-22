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
        assertTrue(prompt.contains(DramaStoryboardCompiler.CLIP_EDGE_STABILITY));
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
        assertTrue(prompt.contains("Dialogue: 局长：洛老弟，别睡了。"));
        assertTrue(prompt.contains("对白执行：" + DramaStoryboardCompiler.BEAT_LIP_SYNC));
        assertTrue(prompt.contains("只念台词正文，不念角色名和冒号"));
        assertFalse(prompt.contains("逐字说出"));
        assertFalse(prompt.contains("对白表演"));
        assertFalse(prompt.contains("以愤怒的语气"));
        assertFalse(prompt.contains("完整人物描述永不缩写"));
        assertFalse(prompt.contains("三视图"));
        assertFalse(prompt.contains("不要剧情场面"));
        assertFalse(prompt.contains("画面中不要出现人物"));
        assertFalse(prompt.contains("浅灰或纯白"));
        assertFalse(prompt.contains("…"));
        assertTrue(prompt.indexOf("<Picture") >= 0);
    }

    @Test
    void liveRealismDialogueStaysCloseInsteadOfShowingTheWholeRoom() {
        String prefix = DramaStyles.require(DramaStyles.LIVE_REALISM).prefix();
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "幻梦侦探社日戏",
                "局长问责",
                "局长：洛老弟，别睡了。",
                "门开",
                "档案落在桌上",
                11,
                List.of("局长", "洛白"),
                2);
        String prompt = DramaStoryboardCompiler.compile(prefix, List.of(), shot).prompt();
        assertTrue(prompt.contains(DramaStoryboardCompiler.LIVE_ACTION_COVERAGE));
        assertTrue(prompt.contains(DramaStoryboardCompiler.CLIP_EDGE_STABILITY));

        String liveRealism = DramaStoryboardCompiler.compile(
                DramaStyles.require(DramaStyles.LIVE_REALISM).prefix(), List.of(), shot).prompt();
        assertTrue(liveRealism.contains(DramaStoryboardCompiler.LIVE_ACTION_COVERAGE),
                "现代写实风也要中近景对白约束，避免场景图把机位拉成整间房");

        String anime = DramaStoryboardCompiler.compile(
                DramaStyles.require(DramaStyles.ANIME_COMIC).prefix(), List.of(), shot).prompt();
        assertFalse(anime.contains(DramaStoryboardCompiler.LIVE_ACTION_COVERAGE));
        assertTrue(anime.contains(DramaStoryboardCompiler.CLIP_EDGE_STABILITY),
                "片头片尾稳住对所有风格生效");
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
        assertTrue(prompt.substring(first, second).contains("台词：" + DramaStoryboardCompiler.BEAT_NO_SPEECH + "。"));
        assertTrue(prompt.substring(second, third).contains("档案（<Picture 3>）"));
        assertTrue(prompt.substring(second, third).contains("台词：" + DramaStoryboardCompiler.BEAT_NO_SPEECH + "。"));
        String speaking = prompt.substring(third, prompt.indexOf("Continuity in:"));
        assertTrue(speaking.contains(
                "台词：角色“局长（<Picture 1>）”以愤怒的语气逐字说出：“你给我站住！”，"
                        + DramaStoryboardCompiler.BEAT_LIP_SYNC + "。"));
        assertFalse(speaking.contains(DramaStoryboardCompiler.BEAT_NO_SPEECH));
        int execution = prompt.indexOf("对白执行：");
        assertTrue(execution > third);
        assertTrue(prompt.substring(execution).startsWith(
                "对白执行：" + DramaStoryboardCompiler.BEAT_DIALOGUE_EXECUTION));
        assertFalse(prompt.substring(execution).contains("你给我站住"));
        assertEquals(1, prompt.split("你给我站住", -1).length - 1);
        assertTrue(prompt.contains("Duration: 12 seconds."));
        assertFalse(prompt.contains("Dialogue:"));
        assertFalse(prompt.contains("Action:"));
        assertFalse(prompt.contains("Camera:"));
        assertFalse(prompt.contains("本镜恰好"));
        assertTrue(prompt.contains("本镜出场人物为局长=<Picture 1>、洛白=<Picture 2>。"));
        assertTrue(prompt.contains("某一镜头没点名的人保持起始站位或留在画外，禁止突然入画"));
        assertFalse(prompt.contains("动作自然克制"));
        assertFalse(prompt.contains("对白表演"));

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

    @Test
    void beatVoiceOverStaysOnThatShotAndGlobalRuleDoesNotRepeatTheLine() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "张总", DramaContract.ASSET_TYPE_CHARACTER, "中年男性", "设定图"),
                ref(2, "李四", DramaContract.ASSET_TYPE_CHARACTER, "青年男性", "设定图"));
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("李四在画面左侧。");
        beats.setBeats(List.of(
                beat("近景", "李四停下脚步。", "走廊", "张总（画外音）：难道是他？"),
                beat("特写", "李四开口。", "接停下", "李四：是我。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "走廊夜戏",
                "张总画外音，李四应答。",
                "张总（画外音）：难道是他？\n李四：是我。",
                "走廊",
                "李四开口",
                11,
                List.of("张总", "李四"),
                2,
                List.of(),
                null,
                null,
                beats);

        String prompt = DramaStoryboardCompiler.compile(prefix, refs, shot).prompt();
        int first = prompt.indexOf("镜头1:");
        int second = prompt.indexOf("镜头2:");
        int execution = prompt.indexOf("对白执行：");
        assertTrue(first >= 0 && first < second && second < execution);
        assertTrue(prompt.substring(first, second).contains(
                "角色“张总（<Picture 1>）”的画外音（使用张总（<Picture 1>）的音色，自画面外传来）逐字念出：“难道是他？”，画面中人物闭嘴，不对口型。"));
        assertFalse(prompt.substring(first, second).contains(DramaStoryboardCompiler.BEAT_LIP_SYNC));
        assertTrue(prompt.substring(second, execution).contains(
                "角色“李四（<Picture 2>）”逐字说出：“是我。”，" + DramaStoryboardCompiler.BEAT_LIP_SYNC + "。"));
        assertTrue(prompt.substring(execution).contains(DramaStoryboardCompiler.BEAT_DIALOGUE_EXECUTION));
        assertTrue(prompt.substring(execution).contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        assertFalse(prompt.substring(execution).contains("难道是他"));
        assertFalse(prompt.substring(execution).contains("是我"));
        assertFalse(prompt.contains("对白表演"));
        assertTrue(prompt.contains("Duration: 11 seconds."));
        assertEquals(1, prompt.split("难道是他", -1).length - 1);
    }

    @Test
    void visiblePromptOmitsTemplateAndKeepsDialogueNamesPlain() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("宴安站在客厅门口。");
        beats.setBeats(List.of(
                beat("中近景", "宴安推门，客厅里没人。", "从门口进入", "宴安：这里有人来过。"),
                beat("特写", "宴安又看向客厅。", "接推门", "宴安：客厅是空的。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "客厅", "宴安进门", "宴安：这里有人来过。\n宴安：客厅是空的。",
                "门外", "站在客厅", 8, List.of("宴安"), 1, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(shot, List.of("宴安", "客厅"));

        assertTrue(visible.startsWith("场景：@客厅\n站位：@宴安站在@客厅门口。"));
        assertTrue(visible.contains("镜头1：中近景。接从门口进入。\n@宴安 推门，@客厅里没人，说：“这里有人来过。”"));
        assertTrue(visible.contains("说：“这里有人来过。”\n\n镜头2：特写。接推门。\n@宴安 又看向@客厅，说：“客厅是空的。”"));
        assertFalse(visible.contains("台词："));
        assertFalse(visible.contains("说：@"));
        assertFalse(visible.contains("<Picture"));
        assertFalse(visible.contains("Must"));
        assertFalse(visible.contains("Active References"));
        assertFalse(visible.contains("Duration"));
        assertFalse(visible.contains("Cast"));
        assertFalse(visible.contains("@宴安："));
    }

    @Test
    void visiblePromptPutsSceneOnItsOwnLineAndMarksPropsOutsideSpeech() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("宴安站在白板前。");
        beats.setBeats(List.of(
                beat("近景", "宴安拿起档案袋，夏安转身。", "接落桌",
                        "宴安：档案袋是空的。\n夏安：没外伤。\n宴安：那就走。\n旁白：夜色已深。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "风扇对着白板", "拿档案", null, null, null, 8,
                List.of("宴安", "夏安"), 2, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(
                shot, List.of("宴安", "夏安", "白板", "档案袋", "幻梦侦探社"), "幻梦侦探社");

        assertTrue(visible.startsWith("场景：@幻梦侦探社。风扇对着@白板"));
        assertTrue(visible.contains("@宴安 拿起@档案袋，说：“档案袋是空的。”"));
        assertTrue(visible.contains("@夏安 转身，说：“没外伤。”"));
        assertTrue(visible.contains("@宴安 说：“那就走。”"));
        assertTrue(visible.contains("旁白：夜色已深。"));
        assertFalse(visible.contains("旁白：“"));
        assertFalse(visible.contains("说：@档案袋"));
        assertFalse(visible.contains("起身"));
        assertEquals("宴安：档案袋是空的。\n夏安：没外伤。\n宴安：那就走。\n旁白：夜色已深。",
                DramaStoryboardCompiler.extractDialogue(visible));

        String rewritten = DramaStoryboardCompiler.writeDialogueBack(
                visible, "宴安：袋子是空的。\n夏安：没有外伤。\n宴安：走吧。\n旁白：天快亮了。");
        assertTrue(rewritten.contains("说：“袋子是空的。”"));
        assertTrue(rewritten.contains("说：“没有外伤。”"));
        assertTrue(rewritten.contains("@宴安 说：“走吧。”"));
        assertTrue(rewritten.contains("旁白：天快亮了。"));
        assertFalse(rewritten.contains("旁白：“"));
        assertFalse(rewritten.contains("档案袋是空的"));
    }

    @Test
    void visiblePromptJoinsSameSpeakerActionAndBlanksBeforeNextShot() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("洛白在病床旁，夏安在门口，心电监护仪在床头。");
        beats.setBeats(List.of(
                beat("近景。下降", "洛白按下怀表开关，庶流从怀表扩散。洛白单手按住怀表，意识沉入梦境深渊。",
                        "接松下针头", "洛白：以梦为桥，意识下潜。入梦！"),
                beat("全景。环绕", "怪物化作代码浪潮，圆形扑向洛白。", "接逐渐扩散",
                        "怪物：不梦……还不梦！\n洛白：代码涌进……不对劲。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "医院ICU，心电监护仪滴答", "入梦", null, null, null, 10,
                List.of("洛白", "夏安"), 2, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(
                shot, List.of("洛白", "夏安", "心电监护仪", "怀表", "市中心医院ICU"), "市中心医院ICU");

        assertEquals("""
                场景：@市中心医院ICU。医院ICU，@心电监护仪滴答
                站位：@洛白在病床旁，@夏安在门口，@心电监护仪在床头。
                镜头1：近景。下降。接松下针头。
                @洛白 按下@怀表开关，庶流从@怀表扩散，说：“以梦为桥，意识下潜。入梦！”
                @洛白 单手按住@怀表，意识沉入梦境深渊。

                镜头2：全景。环绕。接逐渐扩散。
                怪物化作代码浪潮，圆形扑向@洛白，说：“不梦……还不梦！”
                @洛白 说：“代码涌进……不对劲。”
                环境声：不要配乐，只保留对白。
                """.stripTrailing(), visible);
    }

    @Test
    void visiblePromptDropsMentionWhenAssetIsGone() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("@洛白在@白板旁");
        beats.setBeats(List.of(beat("近景", "@夏安看向白板。", "接转身", "夏安：白板呢。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "医院ICU", null, null, null, null, 8,
                List.of("洛白", "夏安"), 2, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(
                shot, List.of("洛白", "夏安"), "医院ICU", List.of("白板"));

        assertTrue(visible.contains("@洛白在白板旁"));
        assertTrue(visible.contains("@夏安 看向白板"));
        assertFalse(visible.contains("@白板"));
        assertTrue(visible.contains("说：“白板呢。”"));
    }

    @Test
    void visiblePromptCitesPropMissingFromActionOnBlockingLine() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("洛白在病床旁");
        beats.setBeats(List.of(beat("近景", "洛白按下开关。", "接松手", "洛白：入梦。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "医院ICU", "入梦", null, null, null, 8,
                List.of("洛白"), 1, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(
                shot, List.of("洛白", "怀表"), "医院ICU", List.of("怀表", "没有图的杯子"));

        assertTrue(visible.contains("站位：@洛白在病床旁，@怀表。"));
        assertTrue(visible.contains("@洛白 按下开关，说：“入梦。”"));
        assertFalse(visible.contains("@没有图的杯子"));
        assertFalse(visible.contains("说：@"));
    }

    @Test
    void visiblePromptDoesNotRepeatPropAlreadyMarkedInAction() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("洛白在病床旁。");
        beats.setBeats(List.of(beat("近景", "洛白按下怀表。", "接松手", "洛白：怀表开启。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "医院ICU", "入梦", null, null, null, 8,
                List.of("洛白"), 1, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(
                shot, List.of("洛白", "怀表"), "医院ICU", List.of("怀表"));

        assertTrue(visible.contains("站位：@洛白在病床旁。"));
        assertFalse(visible.contains("站位：@洛白在病床旁，@怀表"));
        assertTrue(visible.contains("@洛白 按下@怀表，说：“怀表开启。”"));
    }

    @Test
    void visiblePromptShowsAmbianceAndMarksSilentShots() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("宴安站在殿门内。");
        beats.setBeats(List.of(
                beat("中景，中心构图，微仰", "宴安推门。", "从殿门进入", null),
                beat("近景", "宴安停下。", "接推门", "宴安：这里有人来过。")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "殿内金色光柱", "推门", null, null, null, 8,
                List.of("宴安"), 1, List.of(), null, "殿内回声", beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(shot, List.of("宴安"));

        int first = visible.indexOf("镜头1：");
        int second = visible.indexOf("镜头2：");
        assertTrue(first >= 0 && first < second);
        assertTrue(visible.substring(first, second).contains("本镜头无人开口。"));
        assertFalse(visible.substring(second).contains("本镜头无人开口"));
        assertTrue(visible.contains("镜头1：中景，中心构图，微仰。接从殿门进入。"));
        assertTrue(visible.endsWith("环境声：殿内回声。不要配乐，只保留对白。"));
        assertEquals(null, DramaStoryboardCompiler.extractDialogue(
                visible.substring(first, second)));
    }

    @Test
    void visiblePromptWeavesEmotionAndKeepsVariedSpeechVerbs() {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("孙悟空在殿中。");
        beats.setBeats(List.of(
                beat("中景", "局长摔下档案。", "接进门", "局长（愤怒）：你给我站住！"),
                beat("近景", "孙悟空低头合十，平静回应。", "接合十", "孙悟空：弟子，谨遵佛旨。"),
                beat("特写", "孙悟空抬头，问。", "接抬头", "孙悟空（愤怒）：你敢？")));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "大雄宝殿", null, null, null, null, 12,
                List.of("局长", "孙悟空"), 2, List.of(), null, null, beats);

        String visible = DramaStoryboardCompiler.renderVisiblePrompt(shot, List.of("局长", "孙悟空"));

        assertTrue(visible.contains("@局长 摔下档案，愤怒地说：“你给我站住！”"));
        assertTrue(visible.contains("@孙悟空 低头合十，平静回应：“弟子，谨遵佛旨。”"));
        assertFalse(visible.contains("平静回应，说："));
        assertTrue(visible.contains("@孙悟空 抬头，愤怒地问：“你敢？”"));
        assertEquals("""
                局长（愤怒）：你给我站住！
                孙悟空：弟子，谨遵佛旨。
                孙悟空（愤怒）：你敢？""".stripTrailing(), DramaStoryboardCompiler.extractDialogue(visible));

        String rewritten = DramaStoryboardCompiler.writeDialogueBack(
                visible, "局长（愤怒）：别跑！\n孙悟空：弟子遵命。\n孙悟空（愤怒）：放肆！");
        assertTrue(rewritten.contains("愤怒地说：“别跑！”"));
        assertTrue(rewritten.contains("平静回应：“弟子遵命。”"));
        assertTrue(rewritten.contains("愤怒地问：“放肆！”"));
        assertFalse(rewritten.contains("你给我站住"));

        String extra = DramaStoryboardCompiler.writeDialogueBack(
                "镜头1：中景。\n@局长 说：“站住。”",
                "局长：站住。\n洛白（愤怒）：你给我站住！");
        assertTrue(extra.contains("@洛白 愤怒地说：“你给我站住！”"));
        assertTrue(extra.contains("@局长 说：“站住。”"));
    }

    @Test
    void colonNextToQuotesIsDialogueAndNamesInsideStayPlain() {
        String visible = "镜头1：中景。\n@孙悟空 低头合十，平静回应：“弟子，谨遵@佛旨。”";
        assertEquals(List.of("孙悟空"), DramaStoryboardCompiler.narrativeAtNames(visible, List.of("孙悟空", "佛旨")));
        assertEquals("孙悟空：弟子，谨遵佛旨。", DramaStoryboardCompiler.extractDialogue(visible));
        assertEquals("孙悟空：弟子，谨遵佛旨。\n局长（愤怒）：你给我站住！",
                DramaStoryboardCompiler.extractDialogue(
                        "@孙悟空 平静回应：“弟子，谨遵佛旨。”@局长 愤怒地说：“你给我站住！”"));
    }

    @Test
    void assembleDoesNotRepeatEmotionLineAmbianceOrSilence() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "局长", DramaContract.ASSET_TYPE_CHARACTER, "中年男性", "设定图"),
                ref(2, "洛白", DramaContract.ASSET_TYPE_CHARACTER, "青年男性", "设定图"));
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("局长在门口。");
        beats.setBeats(List.of(
                beat("中景", "局长摔下档案。", "接进门", "局长（愤怒）：你给我站住！"),
                beat("近景", "洛白低头。", "接摔档案", null)));
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "办公室", "问责", "局长（愤怒）：你给我站住！", "门外", "站住", 8,
                List.of("局长", "洛白"), 2, List.of(), null, "纸页轻响", beats);
        String visible = DramaStoryboardCompiler.renderVisiblePrompt(shot, List.of("局长", "洛白"));

        String prompt = DramaStoryboardCompiler.assemble(prefix, refs, List.of(), visible, shot).prompt();
        int execution = prompt.indexOf("对白执行：");
        assertTrue(execution > 0);
        assertTrue(prompt.contains("愤怒地说：“你给我站住！”"));
        assertTrue(prompt.contains("本镜头无人开口。"));
        assertTrue(prompt.contains("环境声：纸页轻响。不要配乐，只保留对白。"));
        assertTrue(prompt.substring(execution).contains(DramaStoryboardCompiler.BEAT_DIALOGUE_EXECUTION));
        assertFalse(prompt.substring(execution).contains("以愤怒的语气"));
        assertFalse(prompt.substring(execution).contains("你给我站住"));
        assertFalse(prompt.contains("对白表演"));
        assertFalse(prompt.contains("Ambiance:"));
        assertEquals(1, prompt.split("不要配乐", -1).length - 1);
        assertEquals(1, prompt.split("本镜头无人开口", -1).length - 1);
    }

    @Test
    void assembleKeepsLipSyncWithoutRepeatingVoiceOverOrigin() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        String visible = "@洛白 停下。只闻其声，不出现说话人实体。\n@张总（画外音）：难道是他？\n环境声：不要配乐，只保留对白。";
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "走廊", "画外音", "张总（画外音）：难道是他？", "走廊", "停下", 6,
                List.of("洛白", "张总"), 2);
        String prompt = DramaStoryboardCompiler.assemble(prefix, List.of(), List.of(), visible, shot).prompt();
        assertTrue(prompt.contains("只闻其声，不出现说话人实体"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        assertFalse(prompt.contains("自画面外传来"));
        assertFalse(prompt.contains("口型与这句同步"));
        assertFalse(prompt.contains("Ambiance:"));
        assertEquals(1, prompt.split("难道是他", -1).length - 1);
    }

    @Test
    void assembleBindsNarrativeMentionsOnceAndLeavesDialoguePlain() {
        String prefix = DramaStyles.require(DramaStyles.ANIME_COMIC).prefix();
        List<DramaStoryboardCompiler.ActiveReference> refs = List.of(
                ref(1, "宴安", DramaContract.ASSET_TYPE_CHARACTER, "青年女性", "设定图"),
                ref(2, "客厅", DramaContract.ASSET_TYPE_SCENE, "室内", "客厅"));
        String visible = """
                站位：@宴安站在@客厅门口。
                镜头1：景别：中近景。 动作：@宴安推门。 接续：门口。 台词：宴安：这里有人来过。""";
        DramaStoryboardCompiler.ShotContent shot = new DramaStoryboardCompiler.ShotContent(
                "客厅", "推门", "宴安：这里有人来过。", "门外", "站定", 8,
                List.of("宴安"), 1, List.of(), null, null, beatsOf(visible));

        String prompt = DramaStoryboardCompiler.assemble(prefix, refs, List.of(), visible, shot).prompt();
        String core = prompt.substring(prompt.indexOf("站位："), prompt.indexOf("Continuity in"));

        assertTrue(prompt.contains("<Picture 1> <Picture 2>"));
        assertTrue(prompt.contains("Active References"));
        assertTrue(prompt.contains(DramaStoryboardCompiler.MOVIE_SCENE_MUST));
        assertTrue(core.contains("宴安（<Picture 1>）"));
        assertTrue(core.contains("客厅（<Picture 2>）"));
        assertTrue(core.indexOf("宴安（<Picture 1>）") < core.lastIndexOf("宴安（<Picture 1>）"));
        assertFalse(core.contains("<Picture 3>"));
        assertTrue(core.contains("台词：宴安：这里有人来过。"));
        assertFalse(core.contains("台词：宴安（<Picture"));
        assertFalse(visible.contains("Must"));
    }

    @Test
    void sayLinesAreQuotedButDialogueBoxAndNarrationStayPlain() {
        String stored = "@宴安 拿起杯子，说：杯子是空的。\n旁白：夜色已深。\n@夏安（画外音）：门外有人。";
        String quoted = DramaStoryboardCompiler.quoteSpokenLines(stored);

        assertEquals("@宴安 拿起杯子，说：“杯子是空的。”\n旁白：夜色已深。\n@夏安（画外音）：门外有人。", quoted);
        assertEquals(quoted, DramaStoryboardCompiler.quoteSpokenLines(quoted));
        assertEquals("宴安：杯子是空的。\n旁白：夜色已深。\n夏安（画外音）：门外有人。",
                DramaStoryboardCompiler.extractDialogue("镜头1：" + quoted));

        String outside = DramaStoryboardCompiler.quoteSpokenLines("@宴安 说：“杯子是空的”。");
        assertEquals("@宴安 说：“杯子是空的”。", outside);
        assertEquals("宴安：杯子是空的。", DramaStoryboardCompiler.extractDialogue("镜头1：" + outside));
    }

    @Test
    void narrativeMentionsSkipDialogueAndDuplicateNames() {
        String visible = "动作：@宴安走向@宴安的桌子。 台词：@宴安：我到了。";
        assertEquals(List.of("宴安"), DramaStoryboardCompiler.narrativeAtNames(visible, List.of("宴安")));
        assertEquals("动作：@宴安走向@宴安的桌子。 台词：宴安：我到了。",
                DramaStoryboardCompiler.stripDialogueMentions(visible));
        assertEquals("宴安：我到了。", DramaStoryboardCompiler.extractDialogue("镜头1：" + visible));
    }

    private static DramaShotBeats beatsOf(String ignored) {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("宴安站在客厅门口。");
        beats.setBeats(List.of(beat("中近景", "宴安推门。", "门口", "宴安：这里有人来过。")));
        return beats;
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
