package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextServiceBehaviorTest {

    private static final long SERIES_ID = 7L;
    private static final String RAW = "第一场 办公室 日 内\n洛白：开始了。";
    private static final String GOOD_JSON = """
            {"title":"测试","scenes":[
              {"index":1,"heading":"办公室 日 内","characters":["洛白"],"props":[],
               "location":"办公室","time":"日","interior":true,"action":"洛白抬头。",
               "sound":"","dialogue":[{"speaker":"洛白","line":"开始了。"}]}
            ]}
            """;

    @Test
    void invalidContractRetryIncludesConcreteRepairFeedback() {
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of("不是 JSON", "portable-model", "stop"),
                TextChatResult.of(GOOD_JSON, "portable-model", "stop"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ScriptDoc result = service.parseScript(SERIES_ID, RAW);

        assertEquals(1, result.getScenes().size());
        assertEquals(2, gateway.users.size());
        String repair = gateway.users.get(1);
        assertTrue(repair.contains("返回内容中没有完整 JSON 对象"));
        assertTrue(repair.contains("不是 JSON"));
        assertTrue(repair.contains("ScriptDoc"));
        assertTrue(!repair.equals(gateway.users.get(0)), "第二次请求不能原样重放第一次请求");
    }

    @Test
    void invalidContractNeverFallsBackToRuleGeneratedScript() {
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of("不是 JSON", "portable-model", "stop"),
                TextChatResult.of("仍然不是 JSON", "portable-model", "stop"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        BizException error = assertThrows(BizException.class,
                () -> service.parseScript(SERIES_ID, RAW));

        assertTrue(error.getMessage().contains("ScriptDoc"));
        assertTrue(error.getMessage().contains("返回内容中没有完整 JSON 对象"));
        assertEquals(2, gateway.users.size());
    }

    @Test
    void invalidAssetContractDoesNotAffectScriptStrictness() {
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of("{\"assets\":[]}", "portable-model", "stop"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        AssetDraftList result = service.extractAssets(SERIES_ID, oneScene());

        assertEquals(List.of("洛白", "办公室"),
                result.getAssets().stream().map(AssetDraftList.AssetDraft::getName).toList());
        assertEquals(2, gateway.users.size());
    }

    @Test
    void failedAssetBatchRetainsSkeletonAndContinuesWithNextBatch() {
        AssetDraftList skeleton = skeleton("角色一", "角色二", "角色三", "角色四", "角色五");
        // 并发批次按请求内容路由，避免并行抢占固定回复序列。
        RecordingGateway gateway = new RecordingGateway((user) -> {
            if (user != null && user.contains("角色一")) {
                return TextChatResult.of("", "portable-model", "length");
            }
            return TextChatResult.of("""
                    {"assets":[{"name":"角色五","type":"角色",
                    "description":"模型描述","identity_invariants":"固定五官",
                    "image_prompt":"模型提示词","voice_lock":""}]}
                    """, "portable-model", "stop");
        });

        AssetDraftList result = new DramaTextServiceImpl(gateway)
                .enrichAssets(SERIES_ID, oneScene(), skeleton);

        assertEquals(5, result.getAssets().size());
        assertEquals("角色一", result.getAssets().get(0).getName());
        assertTrue(result.getAssets().get(0).getNeedsHuman());
        assertEquals("模型描述", result.getAssets().get(4).getDescription());
        assertFalse(result.getAssets().get(4).getNeedsHuman());
        assertTrue(gateway.users.size() >= 3);
    }

    @Test
    void enrichmentCannotAddRenameOrRetypeSkeletonAssets() {
        AssetDraftList skeleton = skeleton("洛白");
        RecordingGateway gateway = new RecordingGateway(TextChatResult.of("""
                {"assets":[
                  {"name":"新增","type":"角色","description":"不应出现"},
                  {"name":"洛白改名","type":"场景","description":"不应替换"}
                ]}
                """, "portable-model", "stop"));

        AssetDraftList result = new DramaTextServiceImpl(gateway)
                .enrichAssets(SERIES_ID, oneScene(), skeleton);

        assertEquals(1, result.getAssets().size());
        assertEquals("洛白", result.getAssets().get(0).getName());
        assertEquals("角色", result.getAssets().get(0).getType());
        assertEquals("骨架描述", result.getAssets().get(0).getDescription());
    }

    @Test
    void invalidShotContractNeverFallsBackToRuleGeneratedShots() {
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of("{\"shots\":[]}", "portable-model", "stop"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        BizException error = assertThrows(BizException.class,
                () -> service.draftShots(SERIES_ID, oneScene(), List.of("洛白", "办公室")));

        assertTrue(error.getMessage().contains("ShotDraftList"));
        assertEquals(2, gateway.users.size());
    }

    @Test
    void draftingMinimizesContinuousSameSceneShotsBeforeReturningThem() {
        String shots = """
                {"shots":[
                  {"chapter_index":1,"index":1,"title":"进入","duration_sec":5,
                   "narrative_goal":"洛白走进办公室。","scene":"办公室日戏","dialogue":"",
                   "continuity_in":"门打开","continuity_out":"洛白站在桌前",
                   "character_names":["洛白"],"prop_names":[],"location_name":"办公室","cast_count":1},
                  {"chapter_index":1,"index":2,"title":"拿照片","duration_sec":5,
                   "narrative_goal":"洛白拿起照片。","scene":"办公室日戏","dialogue":"",
                   "continuity_in":"洛白站在桌前","continuity_out":"洛白手持照片",
                   "character_names":["洛白"],"prop_names":[],"location_name":"办公室","cast_count":1},
                  {"chapter_index":1,"index":3,"title":"说话","duration_sec":5,
                   "narrative_goal":"镜头推近洛白。","scene":"办公室日戏","dialogue":"洛白：终于找到了。",
                   "continuity_in":"洛白手持照片","continuity_out":"洛白看向照片",
                   "character_names":["洛白"],"prop_names":[],"location_name":"办公室","cast_count":1}
                ]}
                """;
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of(shots, "portable-model", "stop"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ShotDraftList result = service.draftShots(
                SERIES_ID, oneScene(), List.of("洛白", "办公室"), 15);

        assertEquals(1, result.getShots().size());
        assertEquals(15, result.getShots().get(0).getDurationSec());
        assertEquals("洛白：终于找到了。", result.getShots().get(0).getDialogue());
    }

    @Test
    void parseScriptChunksLongMarkedRawAndRetriesOnlyFailedChunk() {
        // 超 CHAR_BUDGET 才切分；每片最多 2 场。
        String padding = "动作描写".repeat(400);
        StringBuilder raw = new StringBuilder("长剧\n\n");
        for (int i = 1; i <= 4; i++) {
            raw.append("第").append(i).append("场 地点").append(i).append(" 日 内\n")
                    .append(padding).append("\n角色：台词").append(i).append("。\n\n");
        }
        assertTrue(raw.length() > DramaTextChunker.CHAR_BUDGET);
        assertTrue(DramaTextChunker.packRawChunks(raw.toString()).size() >= 2);

        RecordingGateway gateway = new RecordingGateway((user) -> {
            if (user != null && user.contains("上一次结构化输出")) {
                return TextChatResult.of(sceneJson(2, "地点2"), "portable-model", "stop");
            }
            if (user != null && user.contains("第 1/") && user.contains("片段")) {
                return TextChatResult.of("不是 JSON", "portable-model", "stop");
            }
            return TextChatResult.of(sceneJson(4, "地点4"), "portable-model", "stop");
        });
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ScriptDoc doc = service.parseScript(SERIES_ID, raw.toString());

        assertTrue(doc.getScenes().size() >= 2);
        assertTrue(gateway.users.stream().anyMatch(u -> u.contains("片段")));
        long repairs = gateway.users.stream().filter(u -> u.contains("上一次结构化输出")).count();
        assertEquals(1, repairs, "只应重试坏片，不应整本原样重放");
        assertTrue(gateway.users.size() >= 3, "至少：坏片首次 + 坏片修复 + 后续片");
    }

    @Test
    void draftShotsChunksOversizedScriptAndRemapsChapterIndexes() {
        ScriptDoc script = multiSceneScript(4);
        assertEquals(2, DramaTextChunker.packScenes(script.getScenes()).size());

        RecordingGateway gateway = new RecordingGateway((user) -> {
            if (user != null && user.contains("地点3")) {
                return threeSceneShotReply("地点1", "地点2", "地点3");
            }
            return shotReply(1, "地点4");
        });
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ShotDraftList result = service.draftShots(SERIES_ID, script, List.of("洛白", "地点1", "地点2", "地点3", "地点4"));

        assertEquals(4, result.getShots().size());
        assertEquals(List.of(1, 2, 3, 4),
                result.getShots().stream().map(ShotDraftList.ShotDraft::getChapterIndex).toList());
        assertTrue(gateway.users.stream().anyMatch(u -> u.contains("片段")));
        assertEquals(2, gateway.users.size());
    }

    @Test
    void draftShotsChunksTwoShortScenesWithoutNeedingLargeInput() {
        ScriptDoc script = multiSceneScript(2);
        RecordingGateway gateway = new RecordingGateway(twoSceneShotReply("地点1", "地点2"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ShotDraftList result = service.draftShots(SERIES_ID, script, List.of("洛白", "地点1", "地点2"));

        assertEquals(2, result.getShots().size());
        assertEquals(List.of(1, 2),
                result.getShots().stream().map(ShotDraftList.ShotDraft::getChapterIndex).toList());
        assertEquals(1, gateway.users.size(), "不超过每片场数上限时应合并为单次请求");
    }

    @Test
    void shotTruncationBumpsTokensWithoutLengthRepairPayload() {
        ScriptDoc script = multiSceneScript(1);
        RecordingGateway gateway = new RecordingGateway(
                TextChatResult.of("{\"shots\":[{\"title\":\"残缺\"", "portable-model", "length"),
                shotReply(1, "地点1"));
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ShotDraftList result = service.draftShots(SERIES_ID, script, List.of("洛白", "地点1"));

        assertEquals(1, result.getShots().size());
        assertEquals(2, gateway.users.size());
        assertTrue(gateway.users.get(1).contains("为这份剧本出章节"), "截断后应重放原任务");
        assertTrue(!gateway.users.get(1).contains("上一次结构化输出"), "禁止把残缺 JSON 塞进 length-repair");
        assertTrue(!gateway.users.get(1).contains("残缺"));
    }

    @Test
    void draftShotsSkipsFailedChunkAndKeepsSuccessfulOnes() {
        ScriptDoc script = multiSceneScript(4);
        RecordingGateway gateway = new RecordingGateway((user) -> {
            if (user != null && user.contains("第 1/2")) {
                return TextChatResult.of("{\"shots\":[]}", "portable-model", "stop");
            }
            return shotReply(1, "地点4");
        });
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);

        ShotDraftList result = service.draftShots(SERIES_ID, script, List.of("洛白", "地点1", "地点2", "地点3", "地点4"));

        assertEquals(1, result.getShots().size());
        assertEquals(4, result.getShots().get(0).getChapterIndex());
        assertTrue(gateway.users.size() >= 3, "坏片应重试后再跳过，好片继续");
    }

    @Test
    void readShotsSalvagesNestedShotsArray() {
        String messy = """
                说明文字
                {"meta":{"note":"x"},"shots":[]}
                后面又给了一份：
                {"shots":[{"chapter_index":1,"index":1,"title":"开场","duration_sec":8,
                  "character_names":["洛白"],"prop_names":[],"location_name":"地点1","cast_count":1}]}
                """;
        ShotDraftList list = DramaTextServiceImpl.readShots(messy);
        assertEquals(1, list.getShots().size());
        assertEquals("开场", list.getShots().get(0).getTitle());
    }

    @Test
    void assetsForScenesKeepsOnlySceneRelevantNames() {
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setCharacters(List.of("洛白"));
        scene.setLocation("地点1");
        scene.setProps(List.of());
        List<String> picked = DramaTextServiceImpl.assetsForScenes(
                List.of(scene), List.of("洛白", "地点1", "无关角色", "地点9"));
        assertEquals(List.of("洛白", "地点1"), picked);
    }

    private static TextChatResult shotReply(int localChapter, String location) {
        return TextChatResult.of("""
                {"shots":[{"chapter_index":%d,"index":1,"title":"%s开场","duration_sec":10,
                 "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                 "continuity_in":"入画","continuity_out":"出画",
                 "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1}]}
                """.formatted(localChapter, location, location, location), "portable-model", "stop");
    }

    private static TextChatResult twoSceneShotReply(String location1, String location2) {
        return TextChatResult.of("""
                {"shots":[
                  {"chapter_index":1,"index":1,"title":"%s开场","duration_sec":10,
                   "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                   "continuity_in":"入画","continuity_out":"出画",
                   "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1},
                  {"chapter_index":2,"index":1,"title":"%s开场","duration_sec":10,
                   "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                   "continuity_in":"入画","continuity_out":"出画",
                   "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1}
                ]}
                """.formatted(location1, location1, location1, location2, location2, location2),
                "portable-model", "stop");
    }

    private static TextChatResult threeSceneShotReply(String location1, String location2, String location3) {
        return TextChatResult.of("""
                {"shots":[
                  {"chapter_index":1,"index":1,"title":"%s开场","duration_sec":10,
                   "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                   "continuity_in":"入画","continuity_out":"出画",
                   "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1},
                  {"chapter_index":2,"index":1,"title":"%s开场","duration_sec":10,
                   "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                   "continuity_in":"入画","continuity_out":"出画",
                   "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1},
                  {"chapter_index":3,"index":1,"title":"%s开场","duration_sec":10,
                   "narrative_goal":"角色走动。","scene":"%s日戏","dialogue":"",
                   "continuity_in":"入画","continuity_out":"出画",
                   "character_names":["洛白"],"prop_names":[],"location_name":"%s","cast_count":1}
                ]}
                """.formatted(
                        location1, location1, location1,
                        location2, location2, location2,
                        location3, location3, location3),
                "portable-model", "stop");
    }

    @Test
    void remapShotChunkMapsLocalChapterToAbsoluteSceneIndex() {
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(5);
        ShotDraftList part = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setTitle("测试");
        part.getShots().add(shot);
        ShotDraftList.ChapterDraft chapter = new ShotDraftList.ChapterDraft();
        chapter.setIndex(1);
        chapter.setSceneIndex(1);
        part.getChapters().add(chapter);

        DramaTextServiceImpl.remapShotChunk(part, List.of(scene));

        assertEquals(5, part.getShots().get(0).getChapterIndex());
        assertEquals(5, part.getChapters().get(0).getIndex());
        assertEquals(5, part.getChapters().get(0).getSceneIndex());
    }

    private static String sceneJson(int index, String location) {
        return """
                {"title":"长剧","scenes":[
                  {"index":%d,"heading":"%s 日 内","characters":["洛白"],"props":[],
                   "location":"%s","time":"日","interior":true,"action":"走动。",
                   "sound":"","dialogue":[{"speaker":"洛白","line":"台词。"}]}
                ]}
                """.formatted(index, location, location);
    }

    private static ScriptDoc multiSceneScript(int scenes) {
        ScriptDoc doc = new ScriptDoc();
        doc.setTitle("长剧");
        List<ScriptDoc.Scene> list = new ArrayList<>();
        for (int i = 1; i <= scenes; i++) {
            ScriptDoc.Scene scene = new ScriptDoc.Scene();
            scene.setIndex(i);
            scene.setHeading("地点" + i + " 日 内");
            scene.setLocation("地点" + i);
            scene.setCharacters(List.of("洛白"));
            scene.setAction("角色在地点" + i + "走动。");
            scene.setDialogue(List.of());
            list.add(scene);
        }
        doc.setScenes(list);
        return doc;
    }

    private static ScriptDoc oneScene() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("办公室 日 内");
        scene.setLocation("办公室");
        scene.setCharacters(List.of("洛白"));
        scene.setAction("洛白抬头。");
        doc.setScenes(List.of(scene));
        return doc;
    }

    private static AssetDraftList skeleton(String... names) {
        AssetDraftList list = new AssetDraftList();
        for (String name : names) {
            AssetDraftList.AssetDraft draft = new AssetDraftList.AssetDraft();
            draft.setName(name);
            draft.setType("角色");
            draft.setDescription("骨架描述");
            draft.setIdentityInvariants("骨架身份");
            draft.setImagePrompt("骨架提示词");
            draft.setNeedsHuman(true);
            list.getAssets().add(draft);
        }
        return list;
    }

    private static final class RecordingGateway implements TextChatGateway {
        private final List<TextChatResult> replies;
        private final java.util.function.Function<String, TextChatResult> replyFn;
        private final List<String> users = java.util.Collections.synchronizedList(new ArrayList<>());
        private int cursor;

        private RecordingGateway(TextChatResult... replies) {
            this.replies = List.of(replies);
            this.replyFn = null;
        }

        private RecordingGateway(java.util.function.Function<String, TextChatResult> replyFn) {
            this.replies = List.of();
            this.replyFn = replyFn;
        }

        @Override
        public boolean llmAvailable(long seriesId) {
            return true;
        }

        @Override
        public Optional<ResolvedTextModel> resolve(long seriesId) {
            return Optional.of(new ResolvedTextModel(
                    1L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
                    TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model"));
        }

        @Override
        public TextChatResult chat(long seriesId, TextChatRequest req) {
            String user = req.getUser();
            users.add(user);
            if (replyFn != null) {
                return replyFn.apply(user);
            }
            synchronized (this) {
                return replies.get(Math.min(cursor++, replies.size() - 1));
            }
        }
    }
}
