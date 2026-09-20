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
