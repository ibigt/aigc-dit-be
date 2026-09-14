package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextServiceImpl;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 侦探社黄金样例回归：剧本理解层三段链路（分场 → 资产 → 分镜）对固定输入的稳定产出。
 */
class DetectiveClubRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DramaTextServiceImpl service = new DramaTextServiceImpl(unboundGateway());

    /** 本回归钉规则路径：让每个系列都表现为「未绑定文本模型」。 */
    private static TextChatGateway unboundGateway() {
        TextChatGateway gateway = org.mockito.Mockito.mock(TextChatGateway.class);
        org.mockito.Mockito.when(gateway.resolve(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        return gateway;
    }

    private static String fixture(String name) {
        try (InputStream in = DetectiveClubRegressionTest.class.getResourceAsStream("/fixtures/detective-club/" + name)) {
            assertNotNull(in, "缺少黄金样例文件：fixtures/detective-club/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取黄金样例失败：" + name, e);
        }
    }

    private ScriptDoc parsedScript() {
        return service.parseScript(1L, fixture("source.txt"));
    }

    @Test
    void scriptMatchesGoldenSample() throws IOException {
        ScriptDoc expected = MAPPER.readValue(fixture("expected-script.json"), ScriptDoc.class);
        ScriptDoc actual = parsedScript();

        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getLogline(), actual.getLogline());
        assertEquals(5, actual.getScenes().size(), "侦探社必须切成五场");
        assertEquals(expected.getScenes().size(), actual.getScenes().size());

        for (int i = 0; i < expected.getScenes().size(); i++) {
            ScriptDoc.Scene want = expected.getScenes().get(i);
            ScriptDoc.Scene got = actual.getScenes().get(i);
            String at = "第" + (i + 1) + "场 ";
            assertEquals(want.getIndex(), got.getIndex(), at + "index");
            assertEquals(want.getSlug(), got.getSlug(), at + "slug");
            assertEquals(want.getHeading(), got.getHeading(), at + "heading");
            assertEquals(want.getLocation(), got.getLocation(), at + "location");
            assertEquals(want.getTime(), got.getTime(), at + "time");
            assertEquals(want.getInterior(), got.getInterior(), at + "interior");
            assertEquals(want.getCharacters(), got.getCharacters(), at + "characters");
            assertEquals(want.getProps(), got.getProps(), at + "props");
            assertEquals(want.getSound(), got.getSound(), at + "sound");
            assertEquals(want.getAction(), got.getAction(), at + "action");
            assertEquals(dialoguePairs(want), dialoguePairs(got), at + "dialogue");
        }
    }

    @Test
    void firstSceneCastMatchesContract() {
        ScriptDoc.Scene first = parsedScript().getScenes().get(0);

        assertTrue(first.getCharacters().containsAll(List.of("洛白", "夏安", "局长")));
        assertTrue(first.getDialogue().stream().anyMatch(item -> "局长".equals(item.getSpeaker())), "局长闯入必须有对白");
    }

    @Test
    void assetsMatchGoldenSample() throws IOException {
        JsonNode spec = MAPPER.readTree(fixture("expected-assets.json"));
        List<AssetDraftList.AssetDraft> assets = service.extractAssets(1L, parsedScript()).getAssets();

        for (JsonNode want : spec.get("must_include")) {
            AssetDraftList.AssetDraft got = findAsset(assets, want.get("name").asText());
            assertNotNull(got, "资产清单缺少：" + want.get("name").asText());
            assertEquals(want.get("type").asText(), got.getType(), "类型错：" + want.get("name").asText());
            if (want.has("state_tag")) {
                assertEquals(want.get("state_tag").asText(), got.getStateTag(), "state_tag 错：" + got.getName());
            }
            if (want.has("must_split")) {
                assertEquals(want.get("must_split").asBoolean(), Boolean.TRUE.equals(got.getMustSplit()),
                        "must_split 错：" + got.getName());
            }
        }
        assertEquals(spec.get("total_expected").asInt(), assets.size(), "规则抽取不得凭空多出资产");

        for (AssetDraftList.AssetDraft asset : assets) {
            assertTrue(notBlank(asset.getDescription()), "描述不得为空：" + asset.getName());
            assertTrue(notBlank(asset.getIdentityInvariants()), "identity_invariants 不得为空：" + asset.getName());
            assertTrue(notBlank(asset.getImagePrompt()), "image_prompt 不得为空：" + asset.getName());
            assertTrue(Boolean.TRUE.equals(asset.getNeedsHuman()), "未经人工确认的草稿必须 needs_human：" + asset.getName());
        }
    }

    @Test
    void dreamStateIsNeverMergedIntoBase() throws IOException {
        JsonNode spec = MAPPER.readTree(fixture("expected-assets.json"));
        List<AssetDraftList.AssetDraft> assets = service.extractAssets(1L, parsedScript()).getAssets();

        for (JsonNode forbid : spec.get("must_not_merge")) {
            String base = forbid.get("base").asText();
            List<AssetDraftList.AssetDraft> baseRows = assets.stream()
                    .filter(item -> base.equals(item.getName()))
                    .toList();
            assertEquals(1, baseRows.size(), base + " 只应有一条本体");
            assertEquals(DramaContract.STATE_TAG_BASE, baseRows.get(0).getStateTag(), base + " 本体不能带梦境态");
            assertTrue(notBlank(baseRows.get(0).getDescription()));
            assertTrue(!baseRows.get(0).getDescription().contains(forbid.get("state").asText()),
                    base + " 主描述不得混入 " + forbid.get("state").asText());
        }
        assertEquals(2, assets.stream().filter(item -> DramaContract.STATE_TAG_DREAM.equals(item.getStateTag())).count(),
                "入梦态与梦魇各自独立成条");
    }

    @Test
    void shotsMatchGoldenSample() throws IOException {
        JsonNode spec = MAPPER.readTree(fixture("expected-shots.json"));
        ScriptDoc doc = parsedScript();
        List<String> assetNames = service.extractAssets(1L, doc).getAssets().stream()
                .map(AssetDraftList.AssetDraft::getName)
                .toList();
        ShotDraftList drafts = service.draftShots(1L, doc, assetNames);

        assertEquals(spec.get("chapter_count").asInt(), drafts.getChapters().size());
        for (ShotDraftList.ChapterDraft chapter : drafts.getChapters()) {
            assertEquals(chapter.getIndex(), chapter.getSceneIndex(), "章与场必须一一对应");
        }

        List<ShotDraftList.ShotDraft> first = shotsOf(drafts, 1);
        assertTrue(first.size() >= DramaContract.FIRST_SCENE_MIN_SHOTS && first.size() <= DramaContract.FIRST_SCENE_MAX_SHOTS,
                "第一场应 4–8 镜，实得 " + first.size());
        List<String> titles = first.stream().map(ShotDraftList.ShotDraft::getTitle).toList();
        for (JsonNode beat : spec.get("first_chapter_beats")) {
            assertTrue(titles.contains(beat.asText()), "第一场缺少节拍：" + beat.asText());
        }
        assertTrue(first.stream().anyMatch(item -> item.getPropNames().contains(spec.get("scene_one_must_reference_prop").asText())),
                "第一场必须引用古铜怀表");
        assertTrue(first.get(0).getCharacterNames().containsAll(toStringList(spec.get("scene_one_must_reference_characters"))));

        for (ShotDraftList.ShotDraft shot : drafts.getShots()) {
            assertTrue(DramaContract.DURATIONS.contains(shot.getDurationSec()),
                    "时长只许 5–15 秒，实得 " + shot.getDurationSec());
            assertEquals(shot.getCastCount(), shot.getCharacterNames().size(), "cast_count 与人名数组不符：" + shot.getTitle());
            assertTrue(assetNames.containsAll(shot.getCharacterNames()), "分镜引用了不存在的资产名：" + shot.getTitle());
            assertTrue(assetNames.containsAll(shot.getPropNames()), "分镜引用了不存在的道具名：" + shot.getTitle());
            assertTrue(shot.getCharacterNames().stream().noneMatch(name -> name.contains("、") || name.contains(" ")),
                    "人名不得含分隔符：" + shot.getTitle());
            assertDraftFieldsPresent(shot);
        }
        for (int chapterIndex = 1; chapterIndex <= drafts.getChapters().size(); chapterIndex++) {
            int size = shotsOf(drafts, chapterIndex).size();
            int max = chapterIndex == 1 ? DramaContract.FIRST_SCENE_MAX_SHOTS : DramaContract.MAX_SHOTS_PER_CHAPTER;
            assertTrue(size >= DramaContract.MIN_SHOTS_PER_CHAPTER && size <= max,
                    "第" + chapterIndex + "章镜数越界：" + size);
        }
    }

    private static void assertDraftFieldsPresent(ShotDraftList.ShotDraft shot) {
        assertNotNull(shot.getTitle());
        assertNotNull(shot.getContinuityIn());
        assertNotNull(shot.getContinuityOut());
        assertNotNull(shot.getIndex());
        assertNotNull(shot.getChapterIndex());
    }

    private static List<ShotDraftList.ShotDraft> shotsOf(ShotDraftList drafts, int chapterIndex) {
        return drafts.getShots().stream()
                .filter(item -> item.getChapterIndex() != null && item.getChapterIndex() == chapterIndex)
                .toList();
    }

    private static AssetDraftList.AssetDraft findAsset(List<AssetDraftList.AssetDraft> assets, String name) {
        return assets.stream().filter(item -> name.equals(item.getName())).findFirst().orElse(null);
    }

    private static List<String> dialoguePairs(ScriptDoc.Scene scene) {
        List<String> pairs = new ArrayList<>();
        for (ScriptDoc.Dialogue line : scene.getDialogue()) {
            pairs.add(line.getSpeaker() + "|" + line.getLine());
        }
        return pairs;
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
