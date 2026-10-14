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
        assertTrue(luobai.getImagePrompt().contains("真人写真定妆立绘"));
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
        String skeleton = "夏安角色设定图。夏安单独站立，全身可见。"
                + "发型、脸型、五官、肤色、身材比例完整、可重复。"
                + "服装款式、颜色、面料、纹理、配饰和鞋履清晰。"
                + "同一张图内三视图排列：正面全身、侧面半身、面部特写。"
                + "浅灰或纯白干净背景。"
                + "只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。";
        String styleTail = "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感。"
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
        assertEquals(skeleton + "外形与服装必须符合：女性；青年；机敏；身手矫健。" + styleTail, withIdentity);
        assertEquals(skeleton + styleTail, withoutIdentity);
        assertTrue(!withIdentity.contains("身份与外形"));
        assertTrue(!withIdentity.contains("本图只画一名"));
    }

    @Test
    void nonLiveRealismStylesShareTheSameCharacterSheetSkeleton() {
        String identity = "男性；青年；锐利眼神；冷峻气质；修长身形";
        String baseline = null;
        for (DramaStyles.Spec spec : DramaStyles.all()) {
            if (DramaStyles.isRealistic(spec.key())) {
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
    void neonCyberUsesPhotorealCharacterSheetAndExcludesAnime() {
        DramaStyles.Spec spec = DramaStyles.require(DramaStyles.NEON_CYBER);
        assertEquals("霓虹赛博风", spec.name());
        // 写实 prefix 跟远端新 LIVE_REALISM 同族：真人实拍写真摄影 + 禁 3D国漫厚涂/CG数字人
        assertTrue(spec.prefix().contains("霓虹赛博写实风"));
        assertTrue(spec.prefix().contains("赛博都市真人实拍写真摄影"));
        assertTrue(spec.prefix().contains("高对比紫蓝橙红霓虹色"));
        // prefix 只留摄影质感词，场景元素（会污染角色定妆图）必须不存在
        assertTrue(!spec.prefix().contains("霓虹招牌与全息投影"));
        assertTrue(!spec.prefix().contains("雨夜街景"));
        assertTrue(spec.prefix().contains("商业人像与杂志写真质感"));
        assertTrue(spec.prefix().contains("清晰五官与独立发丝"));
        assertTrue(spec.prefix().contains("不要3D国漫厚涂"));
        assertTrue(spec.prefix().contains("不要CG数字人"));
        // 动漫 / 旧半写实 marker 必须不存在
        assertTrue(!spec.prefix().contains("动漫风"));
        assertTrue(!spec.prefix().contains("清晰线稿"));
        assertTrue(!spec.prefix().contains("不要真实人脸"));
        assertTrue(!spec.prefix().contains("半写实3D渲染"));

        String extra = DramaAssetDrafts.styleLookExtra(spec);
        // 写实大类底句（摄影锚点强化版，不用"立绘"等插画措辞）+ 赛博题材句
        assertTrue(extra.contains("真人写真定妆，真实相机霓虹柔光与自然肤质"));
        assertTrue(extra.contains("独立发丝与皮肤纹理清晰"));
        assertTrue(extra.contains("赛博都市发型妆造"));
        assertTrue(extra.contains("机能外套"));
        assertTrue(extra.contains("禁止古装汉服"));
        assertTrue(extra.contains("禁止二次元卡通与3D国漫厚涂"));

        String prompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                spec,
                null,
                null);
        // 走 photoreal 角色骨架：固定四格 + 3/4 侧脸 + 全身三视图，禁用旧三视图措辞
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("固定四格设定拼版"));
        assertTrue(prompt.contains("3/4 侧脸"));
        assertTrue(prompt.contains("正面、侧面、背面"));
        assertTrue(!prompt.contains("同一张图内三视图排列"));
        assertTrue(prompt.contains(extra));

        // 写实大类共用实拍空镜分支（与真人3D 同款窗户光 / 平视拍摄约束）
        String scene = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "霓虹街", spec);
        assertTrue(scene.contains("霓虹街实拍空镜"));
        assertTrue(scene.contains("窗户光或现场实用光"));
        assertTrue(!scene.contains("场景概念图"));
    }

    @Test
    void guofeng3dFollowsLiveRealismPatternAndUsesPhotorealSheet() {
        DramaStyles.Spec spec = DramaStyles.require(DramaStyles.GUOFENG_3D);
        assertEquals("古风写实风", spec.name());
        // 写实 prefix 跟远端新 LIVE_REALISM 同族：国风真人实拍写真摄影 + 禁 3D国漫厚涂/CG数字人
        assertTrue(spec.prefix().contains("国风真人实拍写真摄影"));
        assertTrue(spec.prefix().contains("棚拍柔光与自然肤质"));
        assertTrue(spec.prefix().contains("汉服广袖与发冠金饰"));
        // prefix 只留摄影质感词，场景元素（亭台楼阁会把定妆图带偏成场景插画）必须不存在
        assertTrue(!spec.prefix().contains("亭台楼阁与山水云雾"));
        assertTrue(spec.prefix().contains("商业人像与杂志写真质感"));
        assertTrue(spec.prefix().contains("不要3D国漫厚涂"));
        assertTrue(spec.prefix().contains("不要CG数字人"));
        // 动漫 / 旧半写实 marker 必须不存在（"高精度三维国风渲染"旧版已废）
        assertTrue(!spec.prefix().contains("动漫风"));
        assertTrue(!spec.prefix().contains("清晰线稿"));
        assertTrue(!spec.prefix().contains("不要真实人脸"));
        assertTrue(!spec.prefix().contains("半写实3D渲染"));
        assertTrue(!spec.prefix().contains("高精度三维国风渲染"));

        String extra = DramaAssetDrafts.styleLookExtra(spec);
        // styleLookExtra = 写实大类底句（摄影锚点强化版，不用"立绘"等插画措辞）+ 国风题材句
        assertTrue(extra.contains("真人写真定妆，真实相机棚拍柔光与自然肤质"));
        assertTrue(extra.contains("独立发丝与皮肤纹理清晰"));
        assertTrue(extra.contains("国风古典妆造"));
        assertTrue(extra.contains("汉服广袖与发冠金饰"));
        assertTrue(extra.contains("禁止现代西装夹克与运动鞋"));
        assertTrue(extra.contains("禁止二次元卡通与3D国漫厚涂"));

        String prompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                spec,
                null,
                null);
        // 走 photoreal 角色骨架（跟 LIVE_REALISM / REALISTIC_3D / NEON_CYBER 同组）
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("固定四格设定拼版"));
        assertTrue(prompt.contains("3/4 侧脸"));
        assertTrue(!prompt.contains("同一张图内三视图排列"));
    }

    @Test
    void fixedRealisticTabStylesHaveNoAnimeMarkers() {
        // 写实 tab 已对齐的两个风格：GUOFENG_3D + NEON_CYBER。
        // prefix 不允许出现动漫 marker，也不允许出现旧半写实 marker（远端已把 LIVE_REALISM 改为真人实拍写真，
        // 写实 tab 风格须同族），防止以后回退。
        java.util.Set<String> fixedKeys = java.util.Set.of(
                DramaStyles.GUOFENG_3D,
                DramaStyles.NEON_CYBER);
        for (DramaStyles.Spec spec : DramaStyles.all()) {
            if (!fixedKeys.contains(spec.key())) continue;
            String prefix = spec.prefix();
            if (prefix.contains("动漫风") || prefix.contains("清晰线稿") || prefix.contains("不要真实人脸")
                    || prefix.contains("半写实3D渲染") || prefix.contains("3D国漫轻写实厚涂")) {
                throw new AssertionError(spec.key() + " 的 prefix 仍含动漫或旧半写实 marker: " + prefix);
            }
        }
    }

    @Test
    void liveRealismCharacterPromptUsesFixedSheetWithoutFrontalMugshot() {
        String prefix = DramaStyles.require(DramaStyles.LIVE_REALISM).prefix();
        assertTrue(prefix.contains("真人实拍写真摄影"));
        assertTrue(prefix.contains("商业人像与杂志写真质感"));
        assertTrue(prefix.contains("不要二次元卡通"));
        assertTrue(prefix.contains("不要3D国漫厚涂"));
        assertTrue(!prefix.contains("二次元写实"));
        assertTrue(!prefix.contains("半写实3D渲染"));

        String prompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.LIVE_REALISM),
                null,
                "男性；青年；锐利眼神；冷峻气质；修长身形");
        assertTrue(prompt.contains("洛白角色设定图"));
        assertTrue(prompt.contains("单独站立"));
        assertTrue(prompt.contains("真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，禁止二次元卡通与3D国漫厚涂"));
        assertTrue(prompt.contains("固定四格设定拼版"));
        assertTrue(prompt.contains("3/4 侧脸或 3/4 半身肖像"));
        assertTrue(prompt.contains("全身三视图站立（正面、侧面、背面）"));
        assertTrue(prompt.contains("禁止正面大头照"));
        assertTrue(prompt.contains("不得只出单人全身单帧"));
        assertTrue(prompt.contains("外形与服装必须符合：男性；青年；锐利眼神；冷峻气质；修长身形"));
        assertTrue(!prompt.contains("面部特写"));
        assertTrue(!prompt.contains("正面半身大头照"));
        assertTrue(!prompt.contains("单人 3/4 或正面全身为主"));
        assertTrue(!prompt.contains("半写实 CG"));
        assertTrue(!prompt.contains("非真人照片质感"));
        assertTrue(prompt.contains("浅灰或纯白干净背景"));
        assertTrue(prompt.contains("不要剧情场面"));
        assertTrue(prompt.contains("真人实拍写真摄影"));
        assertTrue(!prompt.contains("身份与外形"));
        assertTrue(!prompt.contains("本图只画一名"));
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

        String animeLookExtra = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                DramaStyles.require(DramaStyles.LIVE_REALISM),
                null,
                null).replace(
                "真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，禁止二次元卡通与3D国漫厚涂。",
                "半写实 CG 角色设定立绘、清晰五官与服装材质，3D国漫轻写实厚涂，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，非摄影棚证件照、非真人照片质感。");
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                animeLookExtra, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.LIVE_REALISM));

        // 写实大类：场景实拍空镜、道具实拍静物
        String scene = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", DramaStyles.require(DramaStyles.LIVE_REALISM));
        assertTrue(scene.contains("幻梦侦探社实拍空镜"));
        assertTrue(scene.contains("窗户光或现场实用光"));
        assertTrue(!scene.contains("场景概念图"));
        assertTrue(scene.contains("不要出现人物"));
        String prop = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_PROP, "古铜怀表", DramaStyles.require(DramaStyles.LIVE_REALISM));
        assertTrue(prop.contains("古铜怀表实拍静物"));
        assertTrue(!prop.contains("道具设定图"));
        assertTrue(prop.contains("不要出现人物"));
    }

    @Test
    void guofeng3dUsesPhotorealSheetMatchingLiveRealismFamily() {
        // 写实 tab 里的 GUOFENG_3D 与 LIVE_REALISM / REALISTIC_3D / NEON_CYBER 共用 photoreal 角色骨架，
        // 并采用 LIVE_REALISM 那套 prefix 写实结构（高精度XX写实 + 半写实3D渲染 + 3D国漫轻写实厚涂）。
        DramaStyles.Spec guofeng = DramaStyles.require(DramaStyles.GUOFENG_3D);
        String guofengPrompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                guofeng,
                null,
                "女性；青年");
        assertTrue(guofengPrompt.contains("真人写真定妆，真实相机棚拍柔光与自然肤质"));
        assertTrue(guofengPrompt.contains("国风古典妆造"));
        assertTrue(guofengPrompt.contains("固定四格设定拼版"));
        assertTrue(guofengPrompt.contains("3/4 侧脸"));
        // 写实四格骨架带摄影锚点，压住拼版自带的 CG 设定表美学
        assertTrue(guofengPrompt.contains("整体是真实相机拍摄的写真质感"));
        assertTrue(guofengPrompt.contains("禁止插画、禁止 CG 渲染"));
        assertTrue(guofengPrompt.contains(guofeng.prefix()));
        assertTrue(!guofengPrompt.contains("同一张图内三视图排列"));
        assertTrue(!guofengPrompt.contains("半写实国风 CG 角色设定立绘"));

        DramaStyles.Spec realistic = DramaStyles.require(DramaStyles.REALISTIC_3D);
        assertEquals("真人3D风", realistic.name());
        assertTrue(realistic.prefix().startsWith("真人3D风，极致写实实拍摄影"));
        assertTrue(realistic.prefix().length() <= 512);
        assertTrue(!realistic.prefix().contains("photorealistic CGI"));
        assertTrue(!realistic.prefix().contains("电影级仿真人数字人"));
        assertTrue(!realistic.prefix().contains("不要真实人脸"));
        assertTrue(!realistic.prefix().contains("非真人照片"));
        assertTrue(!realistic.prefix().contains("原创虚构 CG"));

        String realisticPrompt = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_CHARACTER,
                "洛白",
                DramaContract.STATE_TAG_BASE,
                realistic,
                null,
                "男性；青年；当代常服");
        assertTrue(realisticPrompt.contains("真人实拍定妆"));
        assertTrue(realisticPrompt.contains("毛孔"));
        assertTrue(realisticPrompt.contains("旁边一张正脸近景"));
        assertTrue(realisticPrompt.contains("严格 90 度侧面"));
        assertTrue(realisticPrompt.contains("侧光或单灯打在脸上"));
        assertTrue(realisticPrompt.contains("纯白无缝背景"));
        assertTrue(realisticPrompt.contains("纯白无缝底"));
        assertTrue(realisticPrompt.contains("禁止胸牌"));
        assertTrue(realisticPrompt.contains("不是中性灰摄影棚"));
        assertTrue(!realisticPrompt.contains("中性灰色摄影棚"));
        assertTrue(!realisticPrompt.contains("靠窗的朴素墙面"));
        assertTrue(realisticPrompt.contains("不是三维模型展示台"));
        assertTrue(!realisticPrompt.contains("影视级仿真人数字人"));
        assertTrue(!realisticPrompt.contains("photorealistic CGI"));
        assertTrue(!realisticPrompt.contains("影视级虚拟角色设定板"));
        assertTrue(!realisticPrompt.contains("禁止正面大头照"));
        assertTrue(realisticPrompt.contains("塑料皮肤"));
        assertTrue(realisticPrompt.contains("外形与服装必须符合：男性；青年；当代常服"));
        assertTrue(realisticPrompt.contains(realistic.prefix()));
        assertTrue(!realisticPrompt.contains("次世代数字人质感"));
        assertTrue(!realisticPrompt.contains("非真人照片质感"));
        assertTrue(!realisticPrompt.contains("固定四格设定拼版"));
        assertTrue(!realisticPrompt.contains("面部特写"));
        assertTrue(!realisticPrompt.contains("25 岁"));
        assertTrue(!realisticPrompt.contains("浅蓝白色中国古典长裙"));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                realisticPrompt, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String handEdited = realisticPrompt + "手改：左颊有一颗小痣。";
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                handEdited, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String roomCorner = "洛白角色设定图。真人实拍定妆，窗户光或单灯打在脸上。"
                + "同一张图必须做成同一人的真人实拍定妆：左侧旁边一张正脸近景。"
                + "背景是靠窗的朴素墙面或真实房间一角，不是中性灰摄影棚，不是三维模型展示台。"
                + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                roomCorner, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String greyStudio = "洛白角色设定图。真人实拍定妆，可见毛孔与独立发丝，真实相机拍摄。"
                + "同一张图必须做成同一人的真人实拍定妆：左侧旁边一张正脸近景。"
                + "中性灰色摄影棚实拍背景，真实相机拍摄，不是三维模型展示台。"
                + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                greyStudio, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String oldCgiSheet = "洛白角色设定图。影视级仿真人数字人，photorealistic CGI digital human。"
                + "同一张图必须做成影视级虚拟角色设定板：左侧旁边一张正脸特写，右侧是严格属于同一个角色的正面、严格 90 度侧面、背面三个全身视图。"
                + "中性灰色摄影棚背景。同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldCgiSheet, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String oldRealistic = "洛白角色设定图。同一张图必须做成固定四格设定拼版，不得只出单人全身单帧。"
                + "禁止正面大头照。现代都市常服。电影级三维角色设定，次世代数字人质感，非真人照片质感。"
                + "同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldRealistic, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String threeView = "洛白角色设定图。同一张图内三视图排列：正面全身、侧面半身、面部特写。"
                + "浅灰或纯白干净背景。只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。"
                + "古风3D，高精度三维国风渲染。同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。";
        // GUOFENG_3D 改走 photoreal 骨架（旧三视图 → 新固定四格），三视图 prompt 在 GUOFENG_3D 下应被识别为 obsolete
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                threeView, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.GUOFENG_3D));
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                threeView, DramaContract.ASSET_TYPE_CHARACTER, DramaStyles.REALISTIC_3D));

        String scene = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", realistic);
        assertTrue(scene.contains("幻梦侦探社实拍空镜"));
        assertTrue(scene.contains("窗户光或现场实用光"));
        assertTrue(scene.contains("不要自行加成阁楼"));
        assertTrue(scene.contains("不要出现人物"));
        assertTrue(scene.contains(realistic.prefix()));
        assertTrue(!scene.contains("场景概念图"));
        assertTrue(!scene.contains("广角全景"));
        assertTrue(!scene.contains("对称构图"));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                scene, DramaContract.ASSET_TYPE_SCENE, DramaStyles.REALISTIC_3D));

        String oldScene = "幻梦侦探社场景概念图。场景名称：幻梦侦探社。只描述可固定复用的空间环境，不要写人物动作、对白或剧情事件。"
                + "建筑结构、空间布局、环境陈设和关键道具交代清楚。场景概念图，画面中不要出现人物。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldScene, DramaContract.ASSET_TYPE_SCENE, DramaStyles.REALISTIC_3D));
        // 写实大类（含古风写实）的旧概念图场景自动重拼成实拍空镜
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldScene, DramaContract.ASSET_TYPE_SCENE, DramaStyles.GUOFENG_3D));

        String oldWide = "幻梦侦探社实拍空镜。场景名称：幻梦侦探社。只描述可固定复用的空间环境。"
                + "窗户光或现场实用光，真实相机拍摄的空镜，电影分镜视角，如广角全景、低机位、俯拍或对称构图。"
                + "实拍空镜，画面中不要出现人物。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldWide, DramaContract.ASSET_TYPE_SCENE, DramaStyles.REALISTIC_3D));

        String prop = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_PROP, "古铜怀表", realistic);
        assertTrue(prop.contains("古铜怀表实拍静物"));
        assertTrue(prop.contains("材质"));
        assertTrue(prop.contains("磨损"));
        assertTrue(prop.contains("不要出现人物"));
        assertTrue(prop.contains(realistic.prefix()));
        assertTrue(!prop.contains("道具设定图"));
        assertTrue(!DramaAssetDrafts.shouldReplaceImagePrompt(
                prop, DramaContract.ASSET_TYPE_PROP, DramaStyles.REALISTIC_3D));

        String oldProp = "古铜怀表道具设定图。完整展示外形、材质、磨损和标志性细节，可被手持或放置。"
                + "正面与微距特写，纯色干净背景，静物产品级清晰。画面中不要出现人物。"
                + "同一道具在不同镜头里外形必须保持一致。";
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldProp, DramaContract.ASSET_TYPE_PROP, DramaStyles.REALISTIC_3D));
        // 写实大类（含真人写实）的旧设定图道具自动重拼成实拍静物
        assertTrue(DramaAssetDrafts.shouldReplaceImagePrompt(
                oldProp, DramaContract.ASSET_TYPE_PROP, DramaStyles.LIVE_REALISM));
    }

    @Test
    void realisticCategoryStylesShareLiveActionTemplates() {
        // 写实大类 4 风格（真人写实 / 真人3D / 古风写实 / 霓虹赛博）都在写实基础上实现：
        // 角色为真人定妆拼版（真人3D 为加强定妆转面）、场景为实拍空镜、道具为实拍静物，
        // 且各自叠加自己的题材服饰句与风格句；动漫大类保持概念图 / 设定图分支。
        java.util.Set<String> realisticKeys = java.util.Set.of(
                DramaStyles.LIVE_REALISM,
                DramaStyles.REALISTIC_3D,
                DramaStyles.GUOFENG_3D,
                DramaStyles.NEON_CYBER);
        for (DramaStyles.Spec spec : DramaStyles.all()) {
            if (!realisticKeys.contains(spec.key())) {
                continue;
            }
            assertTrue(spec.realistic(), spec.key());
            String prompt = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_CHARACTER,
                    "洛白",
                    DramaContract.STATE_TAG_BASE,
                    spec,
                    null,
                    null);
            if (DramaStyles.REALISTIC_3D.equals(spec.key())) {
                assertTrue(prompt.contains("真人实拍定妆"), spec.key());
                assertTrue(prompt.contains("严格 90 度侧面"), spec.key());
            } else {
                assertTrue(prompt.contains("固定四格设定拼版"), spec.key());
                assertTrue(prompt.contains("3/4 侧脸"), spec.key());
            }
            assertTrue(prompt.contains("真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质")
                    || prompt.contains("真人写真定妆，真实相机棚拍柔光与自然肤质")
                    || prompt.contains("真人写真定妆，真实相机霓虹柔光与自然肤质")
                    || prompt.contains("真人实拍定妆，可见毛孔与独立发丝"), spec.key());
            assertTrue(prompt.contains(spec.prefix()), spec.key());

            String scene = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", spec);
            assertTrue(scene.contains("幻梦侦探社实拍空镜"), spec.key());
            assertTrue(scene.contains("窗户光或现场实用光"), spec.key());
            assertTrue(scene.contains("真实相机平视拍摄"), spec.key());
            assertTrue(!scene.contains("场景概念图"), spec.key());
            assertTrue(scene.contains(spec.prefix()), spec.key());

            String prop = DramaAssetDrafts.buildPrompt(
                    DramaContract.ASSET_TYPE_PROP, "古铜怀表", spec);
            assertTrue(prop.contains("古铜怀表实拍静物"), spec.key());
            assertTrue(prop.contains("真实相机拍摄"), spec.key());
            assertTrue(!prop.contains("道具设定图"), spec.key());
            assertTrue(prop.contains(spec.prefix()), spec.key());
        }

        // 动漫大类仍是概念图 / 设定图
        DramaStyles.Spec anime = DramaStyles.require(DramaStyles.ANIME_WUXIA);
        assertTrue(!anime.realistic());
        String animeScene = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_SCENE, "幻梦侦探社", anime);
        assertTrue(animeScene.contains("幻梦侦探社场景概念图"));
        String animeProp = DramaAssetDrafts.buildPrompt(
                DramaContract.ASSET_TYPE_PROP, "古铜怀表", anime);
        assertTrue(animeProp.contains("古铜怀表道具设定图"));
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

    @Test
    void skeletonIdentityIsDetectedAndAppearanceBlurbCanPromote() {
        assertTrue(DramaAssetDrafts.isSkeletonIdentity("夏安", "夏安的外貌、发型、服装和年龄必须保持一致"));
        assertTrue(DramaAssetDrafts.isSkeletonIdentity("洛白", "与主身份同一张脸和发型；禁止写成另一个人"));
        assertTrue(DramaAssetDrafts.isSkeletonIdentity(
                "写字楼中楼", "写字楼中楼的空间结构、陈设与光影氛围必须保持一致"));
        assertTrue(DramaAssetDrafts.isSkeletonIdentity(
                "投诉单", "投诉单的外形、材质与标志性细节必须保持一致"));
        assertTrue(!DramaAssetDrafts.isSkeletonIdentity(
                "夏安", "20岁，女，杏眼，黑色短发，白色圆领T恤，深蓝工装背心"));
        assertTrue(DramaAssetDrafts.isAppearanceBlurb("20岁，女，杏眼，黑色短发，白色圆领T恤"));
        assertTrue(!DramaAssetDrafts.isAppearanceBlurb(
                "夏安角色设定图。夏安单独站立，全身可见。同一张图内三视图排列：正面全身、侧面半身、面部特写。"));
    }

    @Test
    void sceneAndPropDoNotUseCharacterAppearanceSkeleton() {
        AssetDraftList list = DramaAssetDrafts.fromScript(scriptWithSceneAndProp(), DramaStyles.require(DramaStyles.ANIME_COMIC));
        AssetDraftList.AssetDraft scene = list.getAssets().stream()
                .filter(item -> DramaContract.ASSET_TYPE_SCENE.equals(item.getType()))
                .findFirst()
                .orElseThrow();
        AssetDraftList.AssetDraft prop = list.getAssets().stream()
                .filter(item -> DramaContract.ASSET_TYPE_PROP.equals(item.getType()))
                .findFirst()
                .orElseThrow();
        assertTrue(scene.getIdentityInvariants() == null || scene.getIdentityInvariants().isBlank());
        assertTrue(prop.getIdentityInvariants() == null || prop.getIdentityInvariants().isBlank());

        AssetDraftList.AssetDraft polluted = new AssetDraftList.AssetDraft();
        polluted.setName("写字楼中楼");
        polluted.setType(DramaContract.ASSET_TYPE_SCENE);
        polluted.setIdentityInvariants("写字楼中楼的外貌、发型、服装和年龄必须保持一致");
        AssetDraftList dirty = new AssetDraftList();
        dirty.setAssets(List.of(polluted));
        DramaAssetDrafts.ensurePrompts(dirty, new ScriptDoc(), DramaStyles.require(DramaStyles.ANIME_COMIC));
        assertTrue(polluted.getIdentityInvariants() == null || polluted.getIdentityInvariants().isBlank());
        assertTrue(DramaAssetDrafts.isCharacterIdentityMisapplied(
                DramaContract.ASSET_TYPE_SCENE, "写字楼中楼的外貌、发型、服装和年龄必须保持一致"));
        assertTrue(!DramaAssetDrafts.isCharacterIdentityMisapplied(
                DramaContract.ASSET_TYPE_CHARACTER, "夏安的外貌、发型、服装和年龄必须保持一致"));

        AssetDraftList.AssetDraft withBlurb = new AssetDraftList.AssetDraft();
        withBlurb.setName("高二2班教室");
        withBlurb.setType(DramaContract.ASSET_TYPE_SCENE);
        withBlurb.setIdentityInvariants("高二2班教室的空间结构、陈设与光影氛围必须保持一致");
        withBlurb.setDescription("高二教室，前后黑板，成排课桌椅，侧窗自然光");
        AssetDraftList blurbList = new AssetDraftList();
        blurbList.setAssets(List.of(withBlurb));
        DramaAssetDrafts.ensurePrompts(blurbList, new ScriptDoc(), DramaStyles.require(DramaStyles.ANIME_COMIC));
        assertEquals("高二教室，前后黑板，成排课桌椅，侧窗自然光", withBlurb.getIdentityInvariants());
        assertTrue(DramaAssetDrafts.isSettingBlurb(
                DramaContract.ASSET_TYPE_SCENE, "高二教室，前后黑板，成排课桌椅，侧窗自然光"));
    }

    private static ScriptDoc scriptWithSceneAndProp() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setLocation("写字楼中楼");
        scene.setProps(List.of("投诉单"));
        scene.setCharacters(List.of("洛白"));
        doc.setScenes(List.of(scene));
        return doc;
    }

    @Test
    void ensurePromptsPromotesAppearanceDescriptionOverSkeletonIdentity() {
        AssetDraftList.AssetDraft draft = new AssetDraftList.AssetDraft();
        draft.setName("夏安");
        draft.setType(DramaContract.ASSET_TYPE_CHARACTER);
        draft.setStateTag(DramaContract.STATE_TAG_BASE);
        draft.setIdentityInvariants("夏安的外貌、发型、服装和年龄必须保持一致");
        draft.setDescription("20岁，女，杏眼，黑色短发刚过耳，白色圆领T恤，深蓝工装背心");
        AssetDraftList list = new AssetDraftList();
        list.setAssets(List.of(draft));

        DramaAssetDrafts.ensurePrompts(list, new ScriptDoc(), DramaStyles.require(DramaStyles.ANIME_COMIC));

        assertEquals("20岁，女，杏眼，黑色短发刚过耳，白色圆领T恤，深蓝工装背心", draft.getIdentityInvariants());
        assertTrue(draft.getImagePrompt().contains("外形与服装必须符合：20岁，女，杏眼"));
    }
}
