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
                3);

        DramaStoryboardCompiler.Result result = DramaStoryboardCompiler.compile(prefix, refs, shot);
        String prompt = result.prompt();

        assertTrue(prompt.startsWith(prefix));
        assertTrue(prompt.contains("<Picture 1> 洛白"));
        assertTrue(prompt.contains("<Picture 2> 夏安"));
        assertTrue(prompt.contains("<Picture 3> 古铜怀表"));
        assertTrue(prompt.contains("<Picture 4> 幻梦侦探社"));
        int p1 = prompt.indexOf("<Picture 1>");
        int p2 = prompt.indexOf("<Picture 2>");
        int p3 = prompt.indexOf("<Picture 3>");
        int p4 = prompt.indexOf("<Picture 4>");
        assertTrue(p1 < p2 && p2 < p3 && p3 < p4);
        assertTrue(prompt.contains("仅作洛白身份与服装，不要继承构图"));
        assertTrue(prompt.contains("可参考陈设与光线"));
        assertTrue(prompt.contains("完整人物描述永不缩写"));
        assertTrue(prompt.contains("本镜恰好 3 人，不得增删角色"));
        assertTrue(prompt.contains("出场：洛白、夏安、局长"));
        assertTrue(prompt.contains("Duration: 5 seconds"));
        assertFalse(prompt.contains("…"));
        assertTrue(prompt.indexOf("<Picture") >= 0);
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

    private static DramaStoryboardCompiler.ActiveReference ref(
            long id, String name, String type, String identity, String descriptor) {
        return new DramaStoryboardCompiler.ActiveReference(id, 1, name, type, descriptor, identity);
    }
}
