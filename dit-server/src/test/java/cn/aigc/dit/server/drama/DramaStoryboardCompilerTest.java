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
        assertTrue(prompt.contains("出场：洛白=<Picture 1>。"));
        assertFalse(prompt.contains("出场：夏安"));
        assertFalse(prompt.contains("Identity lock"));
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

    private static DramaStoryboardCompiler.ActiveReference ref(
            long id, String name, String type, String identity, String descriptor) {
        return new DramaStoryboardCompiler.ActiveReference(id, 1, name, type, descriptor, identity);
    }
}
