package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextChunkerTest {

    @Test
    void shortTwoSceneRawStaysSingleChunk() {
        String raw = "第一场 办公室 日 内\n洛白：开始了。\n\n第二场 街道 夜 外\n夏安：跟上。";
        assertEquals(1, DramaTextChunker.packRawChunks(raw).size(),
                "短剧本未超预算时保持单次");
    }

    @Test
    void manyMarkedScenesStaySingleChunkWhenUnderBudget() {
        String raw = """
                标题

                第一场 甲 日 内
                甲：一。

                第二场 乙 日 内
                乙：二。

                第三场 丙 日 内
                丙：三。

                第四场 丁 日 内
                丁：四。
                """;
        assertEquals(1, DramaTextChunker.packRawChunks(raw).size(),
                "场数多但总长短时不应强制多片，否则 DeepSeek 分场会被无谓拖慢");
    }

    @Test
    void longRawPacksBySceneMarkersUnderBudget() {
        String scene = "第%s场 地点 日 内\n" + "台词内容".repeat(40) + "\n";
        StringBuilder raw = new StringBuilder("标题\n梗概\n\n");
        for (int i = 1; i <= 6; i++) {
            raw.append(scene.formatted(i)).append("\n");
        }
        List<String> chunks = DramaTextChunker.packRawChunks(raw.toString(), 200);
        assertTrue(chunks.size() >= 2, "超预算应按场切成多片");
        assertTrue(chunks.get(0).contains("第1场") || chunks.get(0).contains("标题"));
        assertTrue(chunks.stream().anyMatch(c -> c.contains("第6场")));
    }

    @Test
    void noSceneMarkersKeepsWholeRawEvenWhenLong() {
        String raw = "没有场标记的长散文".repeat(500);
        assertEquals(1, DramaTextChunker.packRawChunks(raw, 100).size());
    }

    @Test
    void shotPackingPacksThreeScenesPerChunkWhenDialogueLight() {
        List<ScriptDoc.Scene> scenes = List.of(
                scene(1, "短"), scene(2, "也短"), scene(3, "三"), scene(4, "四"));
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(scenes);
        assertEquals(2, packs.size(), "对白很少时仍按每 3 场一片");
        assertEquals(3, packs.get(0).size());
        assertEquals(1, packs.get(1).size());
    }

    @Test
    void shotPackingShrinksByDialogueDensity() {
        // 模拟 test2 前三场：17 / 29 / 47 句，原先会并成一片后 soft-skip。
        List<ScriptDoc.Scene> scenes = List.of(
                sceneWithDialogue(1, 17),
                sceneWithDialogue(2, 29),
                sceneWithDialogue(3, 47),
                sceneWithDialogue(4, 8),
                sceneWithDialogue(5, 8));
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(scenes);
        assertTrue(packs.size() >= 4, "对白过密应拆成多片，而不是前三场硬塞一片");
        assertEquals(1, packs.get(0).size(), "17 句已接近预算，不宜再并下一场");
        assertEquals(1, packs.get(1).size());
        assertEquals(1, packs.get(2).size());
        assertTrue(packs.stream().allMatch(p -> p.size() <= 2), "过密时最多 1～2 场/片");
        assertEquals(5, packs.stream().mapToInt(List::size).sum());
    }

    @Test
    void shotPackingAllowsTwoModerateDialogueScenes() {
        List<ScriptDoc.Scene> scenes = List.of(
                sceneWithDialogue(1, 10),
                sceneWithDialogue(2, 10),
                sceneWithDialogue(3, 10));
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(scenes);
        // 10+10=20 <= 24 → 可两场一片；第三场单独或再拼视预算。
        assertEquals(2, packs.size());
        assertEquals(2, packs.get(0).size());
        assertEquals(1, packs.get(1).size());
    }

    @Test
    void singleSceneShotPackStaysOne() {
        assertEquals(1, DramaTextChunker.packScenes(List.of(scene(1, "仅一场"))).size());
    }

    @Test
    void budgetBasedScenePackStillWorks() {
        List<ScriptDoc.Scene> scenes = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            scenes.add(scene(i, "动作描写".repeat(30)));
        }
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(scenes, 80);
        assertTrue(packs.size() >= 2);
        assertEquals(4, packs.stream().mapToInt(List::size).sum());
    }

    @Test
    void scriptParserSplitsRawSegmentsWithHeaderOnFirst() {
        String raw = """
                标题
                梗概一行

                第一场 办公室 日 内
                洛白：甲。

                第二场 街道 夜 外
                夏安：乙。
                """;
        List<String> segments = ScriptParser.splitRawSegments(raw);
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).contains("标题"));
        assertTrue(segments.get(0).contains("第一场"));
        assertTrue(segments.get(1).contains("第二场"));
        assertTrue(!segments.get(1).contains("标题"));
    }

    private static ScriptDoc.Scene scene(int index, String action) {
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(index);
        scene.setHeading("场" + index);
        scene.setAction(action);
        scene.setDialogue(List.of());
        return scene;
    }

    private static ScriptDoc.Scene sceneWithDialogue(int index, int lines) {
        ScriptDoc.Scene scene = scene(index, "动作");
        List<ScriptDoc.Dialogue> dialogue = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            ScriptDoc.Dialogue line = new ScriptDoc.Dialogue();
            line.setSpeaker("角色");
            line.setLine("台词" + i);
            dialogue.add(line);
        }
        scene.setDialogue(dialogue);
        return scene;
    }
}
