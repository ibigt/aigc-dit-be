package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaAssetDraftsTest {

    @Test
    void extractsCharactersPropsLocationAndSplitsDream() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setHeading("幻梦侦探社 日 内");
        scene.setTime("日");
        scene.setInterior(true);
        scene.setAction("风扇转动。夏安在白板贴照片。");
        scene.setCharacters(List.of("洛白", "夏安", "洛白·入梦"));
        scene.setProps(List.of("古铜怀表"));
        scene.setLocation("幻梦侦探社");
        doc.setScenes(List.of(scene));

        AssetDraftList list = DramaAssetDrafts.fromScript(doc);
        assertEquals(5, list.getAssets().size());
        AssetDraftList.AssetDraft dream = list.getAssets().stream()
                .filter(item -> "洛白·入梦".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(DramaContract.ASSET_TYPE_CHARACTER, dream.getType());
        assertEquals(DramaContract.STATE_TAG_DREAM, dream.getStateTag());
        assertTrue(dream.getMustSplit());
        assertTrue(dream.getImagePrompt().contains("三视图"));
        assertTrue(dream.getImagePrompt().contains("浅灰或纯白干净背景"));
        assertTrue(dream.getImagePrompt().contains("二次元漫画风"));
        assertTrue(dream.getImagePrompt().contains("不要剧情场面"));
        assertTrue(!dream.getImagePrompt().contains("风扇"));
        assertTrue(!dream.getImagePrompt().contains("首次出场"));
        assertTrue(!dream.getImagePrompt().contains("角色设定表排版"));
        assertTrue(!dream.getImagePrompt().contains("写实电影质感"));

        AssetDraftList.AssetDraft office = list.getAssets().stream()
                .filter(item -> "幻梦侦探社".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(office.getImagePrompt().contains("不要出现人物"));
        assertTrue(office.getImagePrompt().contains("主光源"));
        assertTrue(office.getImagePrompt().contains("幻梦侦探社 日 内"));

        AssetDraftList.AssetDraft watch = list.getAssets().stream()
                .filter(item -> "古铜怀表".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(watch.getImagePrompt().contains("道具设定图"));
        assertTrue(watch.getImagePrompt().contains("不要出现人物"));
    }

    @Test
    void ensurePromptsFillsMissingPromptAndIdentity() {
        AssetDraftList list = new AssetDraftList();
        AssetDraftList.AssetDraft luobai = new AssetDraftList.AssetDraft();
        luobai.setName("洛白");
        luobai.setType(DramaContract.ASSET_TYPE_CHARACTER);
        list.setAssets(List.of(luobai));

        DramaAssetDrafts.ensurePrompts(list, new ScriptDoc(), DramaStyles.require(DramaStyles.ANIME_COMIC));

        assertTrue(luobai.getImagePrompt().contains("三视图"));
        assertTrue(luobai.getIdentityInvariants().contains("洛白"));
    }

    @Test
    void liveRealismCharacterPromptLocksModernClothes() {
        String prompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER, "洛白", DramaStyles.require(DramaStyles.LIVE_REALISM));
        assertTrue(prompt.contains("当代都市人物"));
        assertTrue(prompt.contains("不要古装"));
        assertTrue(prompt.contains("不要武侠仙侠"));
    }
}
