package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.DramaAssetDrafts;
import cn.aigc.dit.server.drama.DramaAssetText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AssetExtractReq;
import cn.aigc.dit.server.drama.agent.AssetSupervisor;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AssetSupervisor 实现：纯本地校验。
 */
@Component
public class AssetSupervisorImpl implements AssetSupervisor {

    /** identity_invariants 最大长度（与 schema_assets 的「不超过 200 字」对齐）。 */
    private static final int MAX_IDENTITY_LENGTH = 200;
    /** description 最大长度。 */
    private static final int MAX_DESCRIPTION_LENGTH = 100;
    /** 角色/场景/道具 取值。 */
    private static final String TYPE_PROP = "道具";
    private static final String TYPE_ROLE = "角色";
    private static final String TYPE_SCENE = "场景";
    /** must_split=true 状态标签。 */
    private static final Set<String> SPLIT_STATES = Set.of("dream", "costume");

    @Override
    public SupervisorVerdict validate(AgentContext ctx, AssetDraftList assets, Object originalInput) {
        List<SupervisorIssue> issues = new ArrayList<>();
        AssetExtractReq req = (originalInput instanceof AssetExtractReq r) ? r : null;
        ScriptDoc script = req == null ? null : req.script();

        if (assets == null || assets.getAssets() == null || assets.getAssets().isEmpty()) {
            issues.add(new SupervisorIssue(
                    "assets.size()",
                    SupervisorIssue.ERROR,
                    "assets 为空，未抽取任何资产",
                    "至少抽取所有 scene.props 对应的道具条目"));
            return SupervisorVerdict.fail(issues);
        }

        // 按 name 索引
        Map<String, AssetDraftList.AssetDraft> byName = new HashMap<>();
        // 按「类型 + 名字 + 状态标签」索引：同一个角色的本体与入梦版本名字相同（族名），
        // 只差状态标签，按名字索引会把两个版本看成一条。
        Map<AssetIdentity, AssetDraftList.AssetDraft> byIdentity = new HashMap<>();
        for (AssetDraftList.AssetDraft a : assets.getAssets()) {
            if (a.getName() != null && !a.getName().isBlank()) {
                byName.put(a.getName(), a);
                byIdentity.putIfAbsent(new AssetIdentity(a.getType(), a.getName(), stateTagOf(a)), a);
            }
        }

        // 归一化后的 ScriptDoc 是唯一身份词表：资产阶段只能补描述，不能新增别名、改名或改类型。
        // 词表直接取规范资产骨架（与资产 Agent 投影用的是同一份），两边不会各说各话。
        if (script != null && script.getScenes() != null) {
            Set<AssetIdentity> expected = new LinkedHashSet<>();
            for (DramaAssetDrafts.CanonicalAsset canonical : DramaAssetDrafts.canonicalSkeleton(script)) {
                expected.add(new AssetIdentity(canonical.type(), canonical.name(), canonical.stateTag()));
            }
            Map<AssetIdentity, Integer> actualCounts = new HashMap<>();
            for (AssetDraftList.AssetDraft asset : assets.getAssets()) {
                if (asset == null || asset.getName() == null || asset.getName().isBlank()
                        || asset.getType() == null || asset.getType().isBlank()) {
                    continue;
                }
                AssetIdentity identity = new AssetIdentity(asset.getType(), asset.getName(), stateTagOf(asset));
                actualCounts.merge(identity, 1, Integer::sum);
                if (!expected.contains(identity)) {
                    issues.add(new SupervisorIssue(
                            "assets[" + asset.getName() + "]",
                            SupervisorIssue.ERROR,
                            "资产 " + asset.getType() + "/" + asset.getName() + " 不在归一化剧本规范词表中",
                            "删除该条；只能保留归一化剧本已有的角色、场景和道具"));
                }
            }
            for (AssetIdentity identity : expected) {
                int count = actualCounts.getOrDefault(identity, 0);
                if (count == 0) {
                    issues.add(new SupervisorIssue(
                            "assets[" + identity.name() + "]",
                            SupervisorIssue.ERROR,
                            "缺少归一化剧本规范资产 " + identity.type() + "/" + identity.name(),
                            "补充同名同类型资产，只补描述字段"));
                } else if (count > 1) {
                    issues.add(new SupervisorIssue(
                            "assets[" + identity.name() + "]",
                            SupervisorIssue.ERROR,
                            "规范资产重复输出 " + identity.type() + "/" + identity.name(),
                            "同一规范资产只保留一条"));
                }
            }
        }

        // 规则 1：规范骨架里的每个道具都要抽成资产。
        // 这里查的是骨架里的规范名，不是剧本原名：剧本里「拖鞋」与「大号塑料拖鞋」、
        // 「洛白（女主）」与「洛白」归一后各只留一条，按原名查会误报缺失。
        if (script != null && script.getScenes() != null) {
            for (DramaAssetDrafts.CanonicalAsset canonical : DramaAssetDrafts.canonicalSkeleton(script)) {
                if (!TYPE_PROP.equals(canonical.type())) {
                    continue;
                }
                AssetDraftList.AssetDraft a = byIdentity.get(new AssetIdentity(
                        canonical.type(), canonical.name(), canonical.stateTag()));
                if (a == null) {
                    issues.add(new SupervisorIssue(
                            "assets[" + canonical.name() + "]",
                            SupervisorIssue.ERROR,
                            "道具 \"" + canonical.name() + "\" 在规范骨架里但未抽成资产",
                            "新增一条 type=道具、name=\"" + canonical.name() + "\" 的资产"));
                }
            }
        }

        // 规则 2 + 3 + 4：单条目字段检查
        for (int i = 0; i < assets.getAssets().size(); i++) {
            AssetDraftList.AssetDraft a = assets.getAssets().get(i);

            // 字段 2：must_split=false 不与 dream/costume 同名
            if (a.getMustSplit() == null || !a.getMustSplit()) {
                String state = a.getStateTag();
                if (state != null && SPLIT_STATES.contains(state.toLowerCase())) {
                    issues.add(new SupervisorIssue(
                            "assets[" + i + "].state_tag",
                            SupervisorIssue.ERROR,
                            "type=" + a.getType() + " name=" + a.getName() + " 的 state_tag=" + state + " 但 must_split=false",
                            "要么 must_split=true，要么 state_tag=base"));
                }
            }

            // 字段 3：identity_invariants 最好是可用的设定句（空 / 骨架占位句都只算提醒）
            //
            // 这里不再报错：描述写得糙不该把整集分析判死。资产页的空白字段由 ensurePrompts
            // 兜底（角色用默认设定句，场景/道具从描述里提升），流程继续往下走。
            String idInv = a.getIdentityInvariants();
            if (idInv == null || idInv.isBlank()) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].identity_invariants",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 identity_invariants 为空",
                        "补写具体设定：" + identityHint(a.getType())));
            } else if (DramaAssetDrafts.isSkeletonIdentity(a.getName(), idInv)) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].identity_invariants",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 identity_invariants 仍是骨架占位句，没有可用的外形/场景/道具设定",
                        "改写成具体设定：" + identityHint(a.getType())));
            } else if (idInv.length() > MAX_IDENTITY_LENGTH) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].identity_invariants",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 identity_invariants " + idInv.length() + " 字，超过 " + MAX_IDENTITY_LENGTH + " 字上限",
                        "压缩到 " + MAX_IDENTITY_LENGTH + " 字以内"));
            }

            // 字段 4：description 最好有内容，且 ≤ 100
            String desc = a.getDescription();
            if (desc == null || desc.isBlank()) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].description",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 description 为空",
                        "补写一句话描述：角色写身份与外形要点，场景写空间与氛围，道具写用途与外观"));
            } else if (desc.length() > MAX_DESCRIPTION_LENGTH) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].description",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 description " + desc.length() + " 字，超过 " + MAX_DESCRIPTION_LENGTH + " 字上限",
                        "压缩到 " + MAX_DESCRIPTION_LENGTH + " 字以内"));
            }

            // 字段 5：文本里不该有英文。
            //
            // 模型偶尔把性别写成英文再自己改口（「35岁 male? no; 35岁，男，…」），
            // 落库前已由 DramaAssetText 清掉；这里提醒的是清理后仍残留的英文
            // （模型把道具正名、缩写之类写进了外形设定）。只提醒，不判死。
            if (DramaAssetText.hasLatinWord(idInv)) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].identity_invariants",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 identity_invariants 里还有英文",
                        "只写中文：性别用「男」「女」，不要写 male/female，也不要写「male? no」这类自我更正片段"));
            }
            if (DramaAssetText.hasLatinWord(desc)) {
                issues.add(new SupervisorIssue(
                        "assets[" + i + "].description",
                        SupervisorIssue.WARNING,
                        "资产 " + a.getName() + " 的 description 里还有英文",
                        "只写中文：性别用「男」「女」，不要写 male/female，也不要写「male? no」这类自我更正片段"));
            }
        }

        if (issues.isEmpty()) {
            return SupervisorVerdict.pass();
        }
        return SupervisorVerdict.fail(issues);
    }

    /** 按资产类型给出 identity_invariants 的补写指引，作为修复轮的直接反馈。 */
    private String identityHint(String type) {
        if (TYPE_ROLE.equals(type)) {
            return "写清性别、年龄或年龄段、发型发色、脸型五官、身材肤色、上装/外套/下装/鞋履（款式与颜色）";
        }
        if (TYPE_SCENE.equals(type)) {
            return "写清空间结构、陈设、材质与光影";
        }
        if (TYPE_PROP.equals(type)) {
            return "写清外形、材质、磨损与标志性细节";
        }
        return "写清可识别的外形、材质与标志性细节";
    }

    /** 状态标签缺失时按 base 算。 */
    private static String stateTagOf(AssetDraftList.AssetDraft draft) {
        String tag = draft == null ? null : draft.getStateTag();
        return tag == null || tag.isBlank() ? "base" : tag.strip();
    }

    /** 资产身份：类型 + 名字 + 状态标签（同一个角色的本体与版本靠状态标签区分）。 */
    private record AssetIdentity(String type, String name, String stateTag) {}
}
