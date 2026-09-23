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
        assertTrue(office.getImagePrompt().contains("只描述可固定复用的空间环境"));
        assertTrue(!office.getImagePrompt().contains("整体氛围："));
        assertTrue(!office.getImagePrompt().contains("风扇"));
        assertTrue(!office.getImagePrompt().contains("夏安"));
        assertTrue(!office.getImagePrompt().contains("服务剧情情绪"));

        AssetDraftList.AssetDraft watch = list.getAssets().stream()
                .filter(item -> "古铜怀表".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(watch.getImagePrompt().contains("道具设定图"));
        assertTrue(watch.getImagePrompt().contains("不要出现人物"));
        assertTrue(!watch.getImagePrompt().contains("剧情用途"));
        assertTrue(!watch.getImagePrompt().contains("风扇"));
        assertTrue(!watch.getImagePrompt().contains("夏安"));
    }

    @Test
    void shouldReplaceObsoleteScenePromptThatEmbedsPlotAction() {
        String obsolete = "市中心医院ICU场景概念图。场景名称：市中心医院ICU。"
                + "时间与地点：日 内，市中心医院ICU。整体氛围：苍白脸庞人躺在病床，怀表开启后，进入代码梦境，与梦魇怪物战斗。"
                + "建筑结构、空间布局、环境陈设和关键道具交代清楚。墙面、地面、家具、金属、玻璃、木材等材质细节可见。"
                + "主光源、辅光、环境光、明暗关系、色温和阴影方向明确；可用冷暖光、高对比或丁达尔光服务剧情情绪。"
                + "电影分镜视角，如广角全景、低机位、俯拍或对称构图。二次元漫画风。场景概念图，画面中不要出现人物。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                obsolete, DramaContract.ASSET_TYPE_SCENE, DramaStyles.ANIME_COMIC));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                obsolete, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.ANIME_COMIC));

        String rebuilt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "市中心医院ICU", DramaStyles.require(DramaStyles.ANIME_COMIC));
        assertTrue(rebuilt.contains("只描述可固定复用的空间环境"));
        assertTrue(!rebuilt.contains("整体氛围："));
        assertTrue(!rebuilt.contains("怀表"));
        assertTrue(!rebuilt.contains("梦魇"));
    }

    @Test
    void shouldReplaceIncompleteLlmCharacterStub() {
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                "浅灰背景的角色三视图：正面全身、侧面半身、面部特写"));
    }

    @Test
    void shouldReplaceObsoleteLiveRealismThreeViewSheet() {
        String obsolete = "洛白角色设定图。洛白单独站立，全身可见。"
                + "发型、脸型、五官、肤色、身材比例完整、可重复。"
                + "服装款式、颜色、面料、纹理、配饰和鞋履清晰。"
                + "同一张图内三视图排列：正面全身、侧面半身、面部特写。"
                + "浅灰或纯白干净背景。"
                + "只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                + "高精度二次元写实，半写实3D渲染。"
                + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                obsolete, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.LIVE_REALISM));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                obsolete, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.ANIME_COMIC));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                obsolete, DramaContract.ASSET_TYPE_SCENE, DramaStyles.LIVE_REALISM));

        String modern = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.LIVE_REALISM),
                null,
                null);
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                modern, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.LIVE_REALISM));
    }

    @Test
    void ensurePromptsReplacesIncompleteStubWithIdentity() {
        AssetDraftList list = new AssetDraftList();
        AssetDraftList.AssetDraft luobai = new AssetDraftList.AssetDraft();
        luobai.setName("洛白");
        luobai.setType(DramaContract.ASSET_TYPE_CHARACTER);
        luobai.setIdentityInvariants("男性；青年；锐利眼神；冷峻气质；修长身形");
        luobai.setImagePrompt("浅灰背景的角色三视图：正面全身、侧面半身、面部特写");
        list.setAssets(List.of(luobai));

        DramaAssetDrafts.ensurePrompts(list, new ScriptDoc(), DramaStyles.require(DramaStyles.LIVE_REALISM));

        assertTrue(luobai.getImagePrompt().contains("洛白角色设定图"));
        assertTrue(luobai.getImagePrompt().contains("单独站立"));
        assertTrue(luobai.getImagePrompt().contains("半写实 CG 角色设定立绘"));
        assertTrue(luobai.getImagePrompt().contains("现代都市常服"));
        assertTrue(luobai.getImagePrompt().contains("禁止古装武侠汉服仙侠"));
        assertTrue(luobai.getImagePrompt().contains("固定四格设定拼版"));
        assertTrue(luobai.getImagePrompt().contains("禁止正面大头照"));
        assertTrue(luobai.getImagePrompt().contains("外形与服装必须符合：男性；青年；锐利眼神；冷峻气质；修长身形"));
        assertTrue(!luobai.getImagePrompt().contains("面部特写"));
        assertTrue(luobai.getImagePrompt().contains("浅灰或纯白干净背景"));
        assertTrue(luobai.getImagePrompt().contains("不要剧情场面"));
        assertTrue(!luobai.getImagePrompt().contains("身份与外形"));
        assertTrue(luobai.getImagePrompt().length() > 80);
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
    void animeComicCharacterPromptStaysCanonical() {
        String canonical = "夏安角色设定图。夏安单独站立，全身可见。"
                + "发型、脸型、五官、肤色、身材比例完整、可重复。"
                + "服装款式、颜色、面料、纹理、配饰和鞋履清晰。"
                + "同一张图内三视图排列：正面全身、侧面半身、面部特写。"
                + "浅灰或纯白干净背景。"
                + "只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                + "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感。"
                + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        String withIdentity = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "夏安",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.ANIME_COMIC),
                null,
                "女性；青年；机敏；身手矫健");
        String withoutIdentity = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "夏安",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.ANIME_COMIC),
                null,
                null);
        assertEquals(canonical, withIdentity);
        assertEquals(canonical, withoutIdentity);
        assertTrue(!withIdentity.contains("身份与外形"));
        assertTrue(!withIdentity.contains("本图只画一名"));
    }

    @Test
    void nonLiveRealismStylesShareTheSameCharacterSheetSkeleton() {
        String identity = "男性；青年；锐利眼神；冷峻气质；修长身形";
        String baseline = null;
        for (DramaStyles.Spec spec : DramaStyles.all()) {
            if (DramaStyles.LIVE_REALISM.equals(spec.key())) {
                continue;
            }
            String prompt = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_CHARACTER,
                    "洛白",
                    DramaContract.STATE_TAG_BASE,
                    spec,
                    null,
                    identity);
            assertTrue(prompt.contains("洛白角色设定图"), spec.key());
            assertTrue(prompt.contains("单独站立"), spec.key());
            assertTrue(prompt.contains("同一张图内三视图排列：正面全身、侧面半身、面部特写"), spec.key());
            assertTrue(prompt.contains("浅灰或纯白干净背景"), spec.key());
            assertTrue(prompt.contains("只要这一个角色"), spec.key());
            assertTrue(prompt.contains(spec.prefix()), spec.key());

            String scene = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", spec);
            assertTrue(scene.contains("幻梦侦探社场景概念图"), spec.key());
            assertTrue(scene.contains("不要出现人物"), spec.key());
            assertTrue(scene.contains(spec.prefix()), spec.key());

            String prop = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_PROP, "古铜怀表", spec);
            assertTrue(prop.contains("古铜怀表道具设定图"), spec.key());
            assertTrue(prop.contains("不要出现人物"), spec.key());
            assertTrue(prop.contains(spec.prefix()), spec.key());

            String extra = DramaAssetDrafts.styleLookExtra(spec);
            if (!extra.isEmpty()) {
                assertTrue(prompt.contains(extra), spec.key());
            } else {
                assertEquals(DramaStyles.ANIME_COMIC, spec.key());
            }
            String skeleton = prompt.replace(spec.prefix(), "{style}").replace(extra, "");
            if (baseline == null) {
                baseline = skeleton;
            } else {
                assertEquals(baseline, skeleton, spec.key() + " 应与二次元漫画风同一骨架，仅风格句和服饰材质句不同");
            }
        }
    }

    @Test
    void liveRealismCharacterPromptUsesFixedSheetWithoutFrontalMugshot() {
        String prefix = DramaStyles.require(DramaStyles.LIVE_REALISM).prefix();
        assertTrue(prefix.contains("二次元写实"));
        assertTrue(prefix.contains("半写实3D渲染"));
        assertTrue(prefix.contains("写实皮肤毛发与布料质感"));
        assertTrue(!prefix.contains("真人"));
        assertTrue(!prefix.contains("实拍"));
        assertTrue(!prefix.contains("都市写实常服"));

        String prompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.LIVE_REALISM),
                null,
                "男性；青年；锐利眼神；冷峻气质；修长身形");
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("单独站立"));
        assertTrue(prompt.contains("半写实 CG 角色设定立绘、清晰五官与服装材质，3D国漫轻写实厚涂，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，非摄影棚证件照、非真人照片质感"));
        assertTrue(prompt.contains("固定四格设定拼版"));
        assertTrue(prompt.contains("3/4 侧脸或 3/4 半身肖像"));
        assertTrue(prompt.contains("全身三视图站立（正面、侧面、背面）"));
        assertTrue(prompt.contains("禁止正面大头照"));
        assertTrue(prompt.contains("不得只出单人全身单帧"));
        assertTrue(prompt.contains("外形与服装必须符合：男性；青年；锐利眼神；冷峻气质；修长身形"));
        assertTrue(!prompt.contains("面部特写"));
        assertTrue(!prompt.contains("正面半身大头照"));
        assertTrue(!prompt.contains("单人 3/4 或正面全身为主"));
        assertTrue(!prompt.contains("写实皮肤、毛发、布料褶皱与缝线清晰"));
        assertTrue(!prompt.contains("都市写实常服"));
        assertTrue(!prompt.contains("当代都市实拍"));
        assertTrue(prompt.contains("浅灰或纯白干净背景"));
        assertTrue(prompt.contains("不要剧情场面"));
        assertTrue(prompt.contains("高精度二次元写实"));
        assertTrue(!prompt.contains("身份与外形"));
        assertTrue(!prompt.contains("本图只画一名"));
        assertTrue(!prompt.contains("真人写实"));
        assertTrue(!prompt.contains("当代都市写实常服"));
        assertTrue(!prompt.contains("不要洛丽塔"));

        String missingModern = "洛白角色设定图。同一张图必须做成固定四格设定拼版，不得只出单人全身单帧："
                + "左侧一张 3/4 侧脸或 3/4 半身肖像，右侧全身三视图站立（正面、侧面、背面）横排对齐。"
                + "禁止正面大头照、禁止正脸证件照特写、禁止摄影棚大脸特写拼版。"
                + "浅灰或纯白干净背景。只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                + "高精度二次元写实，半写实3D渲染。同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                missingModern, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.LIVE_REALISM));

        String softObsolete = "洛白角色设定图。单人 3/4 或正面全身为主，可附侧面半身；同一张图不要大脸特写拼版。浅灰或纯白干净背景。"
                + "只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                + "高精度二次元写实，半写实3D渲染。同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                softObsolete, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.LIVE_REALISM));

        String scene = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", DramaStyles.require(DramaStyles.LIVE_REALISM));
        assertTrue(scene.contains("幻梦侦探社场景概念图"));
        assertTrue(scene.contains("不要出现人物"));
        String prop = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_PROP, "古铜怀表", DramaStyles.require(DramaStyles.LIVE_REALISM));
        assertTrue(prop.contains("古铜怀表道具设定图"));
        assertTrue(prop.contains("不要出现人物"));
    }

    @Test
    void shouldKeepCompleteUserPrompt() {
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                "夏安角色设定图。夏安单独站立，全身可见。发型、脸型、五官、肤色、身材比例完整、可重复。"
                        + "服装款式、颜色、面料、纹理、配饰和鞋履清晰。同一张图内三视图排列：正面全身、侧面半身、面部特写。"
                        + "浅灰或纯白干净背景。只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                        + "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感。"
                        + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。"));
    }
}
