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
    void unboundSeriesExtractsAssetsByRulesWithoutHttp() {
        DramaTextServiceImpl service = boundService();

        AssetDraftList drafts = service.extractAssets(UNBOUND, oneScene());

        assertEquals(2, drafts.getAssets().size(), "规则路径：人物 + 场景");
        verify(gateway, never()).chat(anyLong(), any());
    }

    @Test
    void dreamStateIsSplitEvenWhenModelForgetStateTag() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of(ASSETS_JSON, "m", "stop"));

        List<AssetDraftList.AssetDraft> assets = service.extractAssets(BOUND, oneScene()).getAssets();

        AssetDraftList.AssetDraft dream = named(assets, "洛白·入梦");
        assertEquals(DramaContract.STATE_TAG_DREAM, dream.getStateTag());
        assertTrue(dream.getMustSplit());
        assertEquals(DramaContract.STATE_TAG_BASE, named(assets, "洛白").getStateTag());
        assertFalse(named(assets, "洛白").getMustSplit());
    }

    @Test
    void duplicateAndUnnamedAssetsAreDroppedAndBlanksMarkedNeedsHuman() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of(ASSETS_JSON, "m", "stop"));

        List<AssetDraftList.AssetDraft> assets = service.extractAssets(BOUND, oneScene()).getAssets();

        assertEquals(4, assets.size(), "重复的洛白与无名条目都要丢掉");
        assertTrue(assets.stream().noneMatch(item -> "  ".equals(item.getName())));
        assertTrue(named(assets, "夏安").getNeedsHuman(), "identity_invariants 为空必须标 needs_human");
    }

    @Test
    void emptyAssetListFallsBackToRules() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("{\"assets\":[]}", "m", "stop"));

        assertEquals(2, service.extractAssets(BOUND, oneScene()).getAssets().size());
    }

    @Test
    void assetTransportFailureFallsBackToRules() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenThrow(new BizException(ErrorCode.INTERNAL, "超时"));

        assertFalse(service.extractAssets(BOUND, oneScene()).getAssets().isEmpty());
    }

    @Test
    void shotDurationsAreClampedAndInventedNamesRemoved() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of(SHOTS_JSON, "m", "stop"));

        List<ShotDraftList.ShotDraft> shots = service
                .draftShots(BOUND, oneScene(), List.of("洛白", "古铜怀表"))
                .getShots();

        ShotDraftList.ShotDraft first = shots.get(0);
        assertTrue(shots.stream().allMatch(item -> Integer.valueOf(DramaContract.DEFAULT_DURATION_SEC).equals(item.getDurationSec())),
                "拆镜时长必须统一成指定秒数");
        assertEquals(DramaContract.DEFAULT_DURATION_SEC, first.getDurationSec(), "7 秒不在白名单内");
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
    void unboundOrEmptyShotsFallBackToRules() {
        DramaTextServiceImpl service = boundService();

        List<String> names = List.of("洛白", "古铜怀表");
        assertEquals(4, service.draftShots(UNBOUND, oneScene(), names).getShots().size(), "规则路径第一场 4 镜");

        when(gateway.chat(anyLong(), any())).thenReturn(TextChatResult.of("{\"shots\":[]}", "m", "stop"));
        assertEquals(4, service.draftShots(BOUND, oneScene(), names).getShots().size());
    }
}
