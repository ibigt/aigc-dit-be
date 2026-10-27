package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.drama.DramaContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产草稿与分镜草稿两条文本模型路径的合同修复。
 */
@ExtendWith(MockitoExtension.class)
class DramaTextDraftPathsTest {

    private static final long UNBOUND = 1L;
    private static final long BOUND = 2L;

    private static final String ASSETS_JSON = """
            {"assets":[
              {"name":"洛白","type":"角色","description":"青年男性，清冷骨相","identity_invariants":"黑碎盖"},
              {"name":"洛白·入梦","type":"角色","state_tag":"","description":"光点抽离漂浮","identity_invariants":"同一张脸"},
              {"name":"古铜怀表","type":"道具","description":"铜壳怀表","identity_invariants":"表链磨损"},
              {"name":"洛白","type":"角色","description":"重复条目","identity_invariants":"x"},
              {"name":"  ","type":"角色","description":"无名丢弃"},
              {"name":"夏安","type":"角色","description":"缺少身份不变量"}
            ]}
            """;

    private static final String SHOTS_JSON = """
            {"shots":[
              {"title":"侦探社开场","duration_sec":7,"character_names":["洛白","路人甲"],
               "prop_names":["古铜怀表","不存在道具"],"location_name":"不存在的场景","cast_count":9},
              {"chapter_index":1,"title":"局长闯入","duration_sec":15,"character_names":["洛白"]}
            ]}
            """;

    @Mock
    private TextChatGateway gateway;

    private DramaTextServiceImpl boundService() {
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);
        lenient().when(gateway.resolve(UNBOUND)).thenReturn(Optional.empty());
        lenient().when(gateway.resolve(BOUND)).thenReturn(Optional.of(
                new ResolvedTextModel(5L, "Agnes 对话", TextAdapterCatalog.AGNES_TEXT,
                        "官方", "https://api.agnes-ai.cn/v1", TextAdapterCatalog.AGNES_CHAT_MODEL)));
        return service;
    }

    private static ScriptDoc oneScene() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("幻梦侦探社 日 内");
        scene.setLocation("幻梦侦探社");
        scene.setAction("风扇转动。洛白坐在桌前。");
        scene.getCharacters().add("洛白");
        doc.getScenes().add(scene);
        return doc;
    }

    private static AssetDraftList.AssetDraft named(List<AssetDraftList.AssetDraft> assets, String name) {
        return assets.stream().filter(item -> name.equals(item.getName())).findFirst().orElseThrow();
    }

    @Test
    void unboundSeriesReturnsDeterministicAssetSkeleton() {
        DramaTextServiceImpl service = boundService();

        AssetDraftList result = service.extractAssets(UNBOUND, oneScene());

        assertEquals(List.of("洛白", "幻梦侦探社"), result.getAssets().stream()
                .map(AssetDraftList.AssetDraft::getName).toList());
        verify(gateway, never()).chat(anyLong(), any());
    }

    @Test
    void dreamStateIsSplitEvenWhenModelForgetStateTag() {
        AssetDraftList list = DramaTextServiceImpl.normalizeAssets(
                DramaTextServiceImpl.parseJson(ASSETS_JSON, AssetDraftList.class));

        List<AssetDraftList.AssetDraft> assets = list.getAssets();
        AssetDraftList.AssetDraft dream = assets.stream()
                .filter(item -> "洛白".equals(item.getName())
                        && DramaContract.STATE_TAG_DREAM.equals(item.getStateTag()))
                .findFirst()
                .orElseThrow();
        assertEquals(DramaContract.STATE_TAG_DREAM, dream.getStateTag());
        assertTrue(dream.getMustSplit());
        assertEquals(DramaContract.STATE_TAG_BASE, named(assets, "洛白").getStateTag());
        assertFalse(named(assets, "洛白").getMustSplit());
    }

    @Test
    void duplicateAndUnnamedAssetsAreDroppedAndBlanksMarkedNeedsHuman() {
        AssetDraftList list = DramaTextServiceImpl.normalizeAssets(
                DramaTextServiceImpl.parseJson(ASSETS_JSON, AssetDraftList.class));

        List<AssetDraftList.AssetDraft> assets = list.getAssets();
        assertEquals(4, assets.size(), "重复的洛白与无名条目都要丢掉");
        assertTrue(assets.stream().noneMatch(item -> "  ".equals(item.getName())));
        assertTrue(named(assets, "夏安").getNeedsHuman(), "identity_invariants 为空必须标 needs_human");
    }

    @Test
    void emptyAssetListRetainsDeterministicSkeleton() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("{\"assets\":[]}", "m", "stop"));

        AssetDraftList result = service.extractAssets(BOUND, oneScene());
        assertEquals(2, result.getAssets().size());
        assertTrue(result.getAssets().stream().allMatch(item -> Boolean.TRUE.equals(item.getNeedsHuman())));
    }

    @Test
    void assetTransportFailureRetainsDeterministicSkeleton() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenThrow(new BizException(ErrorCode.INTERNAL, "超时"));

        AssetDraftList result = service.extractAssets(BOUND, oneScene());
        assertEquals(2, result.getAssets().size());
    }

    @Test
    void shotDurationsKeepModelValuesAndDropInventedNames() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of(SHOTS_JSON, "m", "stop"));

        List<ShotDraftList.ShotDraft> shots = service
                .draftShots(BOUND, oneScene(), List.of("洛白", "古铜怀表"))
                .getShots();

        ShotDraftList.ShotDraft first = shots.get(0);
        assertEquals(7, first.getDurationSec(), "模型给的 7 秒应保留，不得整表覆盖");
        assertEquals(15, shots.get(1).getDurationSec());
        assertEquals(List.of("洛白"), first.getCharacterNames(), "路人甲没有这个资产");
        assertEquals(List.of("古铜怀表"), first.getPropNames());
        assertEquals(1, first.getCastCount(), "cast_count 必须与人名数组一致");
        org.junit.jupiter.api.Assertions.assertNull(first.getLocationName());
    }

    @Test
    void shotIndexesAreRenumberedWithinChapter() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of(SHOTS_JSON, "m", "stop"));

        ShotDraftList drafts = service.draftShots(BOUND, oneScene(), List.of("洛白", "古铜怀表"));

        assertEquals(List.of(1, 2), drafts.getShots().stream().map(ShotDraftList.ShotDraft::getIndex).toList());
        assertEquals(1, drafts.getChapters().size(), "模型没给 chapters 时按场补齐");
        assertEquals("幻梦侦探社 日 内", drafts.getChapters().get(0).getName());
    }

    @Test
    void shotsAreSortedByChapterThenIndex() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("""
                {"shots":[
                  {"chapter_index":1,"index":2,"title":"前场第二","duration_sec":7,
                   "continuity_in":"洛白站在桌前","character_names":["洛白"]},
                  {"chapter_index":1,"index":1,"title":"前场第一","duration_sec":5,
                   "continuity_out":"洛白站在桌前","character_names":["洛白"]},
                  {"chapter_index":2,"index":1,"title":"后场先出","duration_sec":6,"character_names":["洛白"]}
                ]}
                """, "m", "stop"));

        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene first = new ScriptDoc.Scene();
        first.setIndex(1);
        first.setHeading("第一场");
        ScriptDoc.Scene second = new ScriptDoc.Scene();
        second.setIndex(2);
        second.setHeading("第二场");
        doc.setScenes(List.of(first, second));

        List<String> titles = service.draftShots(BOUND, doc, List.of("洛白")).getShots()
                .stream().map(ShotDraftList.ShotDraft::getTitle).toList();
        assertEquals(List.of("前场第一", "后场先出"), titles,
                "同场且总时长不超过 15 秒的前两镜应合并");
    }

    @Test
    void shotDraftAcceptsMinimaxStyleCommentsAndTrailingCommas() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("""
                {
                  // MiniMax-M3 sometimes emits non-standard comments
                  "shots": [
                    {
                      "title": "侦探社开场",
                      "duration_sec": 7,
                      "character_names": ["洛白"],
                      "prop_names": ["古铜怀表"],
                    },
                  ],
                }
                """, "MiniMax-M3", "stop"));

        List<ShotDraftList.ShotDraft> shots = service
                .draftShots(BOUND, oneScene(), List.of("洛白", "古铜怀表"))
                .getShots();

        assertEquals(1, shots.size());
        assertEquals("侦探社开场", shots.get(0).getTitle());
        assertEquals(7, shots.get(0).getDurationSec());
        assertEquals(List.of("洛白"), shots.get(0).getCharacterNames());
    }

    @Test
    void unboundOrEmptyShotsFailExplicitly() {
        DramaTextServiceImpl service = boundService();

        List<String> names = List.of("洛白", "古铜怀表");
        BizException unbound = assertThrows(BizException.class,
                () -> service.draftShots(UNBOUND, oneScene(), names));
        assertTrue(unbound.getMessage().contains("未绑定文本模型"));

        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("{\"shots\":[]}", "m", "stop"));
        BizException invalid = assertThrows(BizException.class,
                () -> service.draftShots(BOUND, oneScene(), names));
        assertTrue(invalid.getMessage().contains("ShotDraftList"));
    }
}
