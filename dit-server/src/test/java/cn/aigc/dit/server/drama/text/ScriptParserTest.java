package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptParserTest {

    private static final String SAMPLE = """
            幻梦侦探社
            洛白靠古铜怀表入梦办案，第一桩案子来自局长。

            第一场 幻梦侦探社 日 内
            人物：洛白、夏安、局长
            道具：古铜怀表、厚档案、白板
            声音：风扇；档案摔桌；怀表咔哒
            △ 风扇转动。夏安在白板贴照片。洛白用书盖脸。
            局长：洛老弟，别睡了。
            洛白：找猫抓狗去居委会。但这案子……
            △ 洛白掏出古铜怀表。

            第二场 市中心医院ICU 日 内
            △ 仪器滴答。徐医生翻病历。
            徐医生：他家属在门外等。

            第三场 幻梦侦探社 夜 内
            洛白：怀表不是给我的。

            第四场 静心心理诊所 日 内
            夏安：这里的催眠椅是新的。

            第五场 幻梦侦探社 日 内
            洛白：案子结了。
            """;

    @Test
    void splitsFiveScenesWithContinuousIndex() {
        ScriptDoc doc = ScriptParser.parse(SAMPLE);

        assertEquals(5, doc.getScenes().size());
        for (int i = 0; i < doc.getScenes().size(); i++) {
            assertEquals(i + 1, doc.getScenes().get(i).getIndex());
        }
        assertEquals("幻梦侦探社", doc.getTitle());
        assertTrue(doc.getLogline().contains("古铜怀表"));
    }

    @Test
    void firstSceneKeepsCastPropsAndSound() {
        ScriptDoc.Scene first = ScriptParser.parse(SAMPLE).getScenes().get(0);

        assertTrue(first.getCharacters().containsAll(List.of("洛白", "夏安", "局长")));
        assertTrue(first.getProps().containsAll(List.of("古铜怀表", "厚档案", "白板")));
        assertEquals("风扇；档案摔桌；怀表咔哒", first.getSound());
        assertEquals(2, first.getDialogue().size());
        assertEquals("局长", first.getDialogue().get(0).getSpeaker());
        assertTrue(first.getDialogue().get(0).getLine().startsWith("洛老弟"));
    }

    @Test
    void headingYieldsLocationTimeAndInterior() {
        ScriptDoc.Scene first = ScriptParser.parse(SAMPLE).getScenes().get(0);

        assertEquals("幻梦侦探社", first.getLocation());
        assertEquals("日", first.getTime());
        assertEquals(Boolean.TRUE, first.getInterior());
        assertEquals("幻梦侦探社 日 内", first.getHeading());
        assertEquals("scene-1", first.getSlug());
    }

    @Test
    void speakersBecomeCharactersWithoutMetaLine() {
        ScriptDoc doc = ScriptParser.parse(SAMPLE);
        ScriptDoc.Scene third = doc.getScenes().get(2);

        assertEquals(List.of("洛白"), third.getCharacters());
        assertNull(third.getAction());

        ScriptDoc.Scene first = doc.getScenes().get(0);
        assertTrue(first.getAction().contains("洛白掏出古铜怀表。"));
        assertTrue(!first.getAction().contains("。。"));
    }

    @Test
    void metaLinesNeverLeakIntoDialogue() {
        ScriptDoc.Scene first = ScriptParser.parse(SAMPLE).getScenes().get(0);

        assertTrue(first.getDialogue().stream().noneMatch(item -> List.of("人物", "道具", "声音").contains(item.getSpeaker())));
        assertTrue(first.getAction().contains("风扇转动"));
    }

    @Test
    void everyContentLineLandsInActionOrDialogue() {
        ScriptDoc doc = ScriptParser.parse(SAMPLE);
        ScriptDoc.Scene fourth = doc.getScenes().get(3);

        assertEquals(1, fourth.getDialogue().size());
        assertNull(fourth.getAction());
        assertEquals("这里的催眠椅是新的。", fourth.getDialogue().get(0).getLine());
    }

    @Test
    void synopsisWithoutSceneMarkerBecomesSingleScene() {
        ScriptDoc doc = ScriptParser.parse("洛白和夏安接到一桩委托，怀表引出入梦。");

        assertEquals(1, doc.getScenes().size());
        assertEquals(1, doc.getScenes().get(0).getIndex());
        assertTrue(doc.getScenes().get(0).getAction().contains("委托"));
        assertTrue(doc.getScenes().get(0).getDialogue().isEmpty());
    }

    @Test
    void toleratesArabicMarkersCrlfAndTrailingSpaces() {
        String raw = "场 1 幻梦侦探社 日 内  \r\n洛白：开始了。\r\n\r\n场 2 街道 夜 外\r\n夏安：人跑了。\r\n";
        ScriptDoc doc = ScriptParser.parse(raw);

        assertEquals(2, doc.getScenes().size());
        assertEquals("幻梦侦探社", doc.getScenes().get(0).getLocation());
        assertEquals(Boolean.FALSE, doc.getScenes().get(1).getInterior());
        assertEquals("夜", doc.getScenes().get(1).getTime());
    }

    @Test
    void blankInputYieldsEmptyDocument() {
        assertTrue(ScriptParser.parse(null).getScenes().isEmpty());
        assertTrue(ScriptParser.parse("   \n  ").getScenes().isEmpty());
    }
}
