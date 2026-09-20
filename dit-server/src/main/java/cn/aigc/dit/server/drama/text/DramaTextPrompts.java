package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaContract;

/**
 * 剧本理解层的系统提示。字段名与 {@link ScriptDoc} / {@link AssetDraftList} / {@link ShotDraftList}
 * 的合同逐一对应，禁止模型发明 key——下游按 JSONB 落库并渲染表单。
 */
public final class DramaTextPrompts {

    private static final String COMMON = """
            你是漫剧流水线的「剧本理解层」，只做文本进、结构化 JSON 出。
            硬性要求：
            1. 只输出一个 JSON 对象，不要 Markdown 代码围栏，不要解释文字。
            2. 键名必须与给定 schema 完全一致，一律 snake_case，不得增删改名。
            3. 字符串字段无内容时给空字符串，数组字段无内容时给空数组，不要给 null。
            4. 人物、道具、地点名必须原样沿用剧本里出现的写法，不要改写、不要简称、不要合并同义称呼。
            5. 剧本里没有的信息不要发明。
            6. 输出要尽量精简：能用关键词就不用整句，不要堆砌修饰语或剧情外的解释；
               各 string 字段的具体长度上限见对应 schema 的规则。
            """;

    private static final String SCHEMA_SCRIPT = """
            ScriptDoc 结构：
            {
              "title": "剧本名",
              "logline": "一句话梗概",
              "style_prefix": "风格短句，没有就留空",
              "scenes": [
                {
                  "index": 1,
                  "slug": "kebab-英文短名",
                  "heading": "幻梦侦探社 日 内",
                  "characters": ["洛白", "夏安", "局长"],
                  "props": ["古铜怀表", "厚档案", "白板"],
                  "location": "幻梦侦探社",
                  "time": "日",
                  "interior": true,
                  "action": "风扇转动。夏安在白板贴照片。洛白用书盖脸。",
                  "sound": "风扇；档案摔桌；怀表咔哒",
                  "dialogue": [
                    { "speaker": "局长", "line": "洛老弟，别睡了。" },
                    { "speaker": "洛白", "line": "找猫抓狗去居委会。但这案子……" }
                  ]
                }
              ]
            }
            index 从 1 连续递增；interior 内景为 true、外景为 false；
            characters 收录本场出场与说话的所有人物；
            props 只收录主要道具，并按剧情重要性从高到低排列，每场最多 3 个：
            - 保留推动情节、触发关键动作/冲突、承载线索，或能稳定识别角色身份的道具；
            - 普通家具、环境陈设、服装、餐具、文具、消耗品及仅在背景中一闪而过的物品不要收录；
            - 同一道具不要重复；没有主要道具时返回空数组，不要为了凑数添加物品；
            action 只写可视动作与环境，不要写心理独白，不要把台词搬进来。
            - action 控制在 80 字以内，只写可视动作与环境的最小集合。
            - sound 写 1~2 个关键声音关键词，总长不超过 20 字。
            - dialogue 严格保留剧本原文，不要扩写、解释或补全台词。
            """;

    private static final String SCHEMA_ASSETS = """
            AssetDraftList 结构：
            {
              "assets": [
                {
                  "name": "洛白",
                  "type": "角色|场景|道具",
                  "state_tag": "base|dream|costume",
                  "description": "完整人物/场景/道具描述，永不缩写",
                  "identity_invariants": "男性；青年；清冷骨相；黑碎盖；修长；深灰立领长风衣；黑色长裤；皮鞋；锐利眼神",
                  "image_prompt": "",
                  "voice_lock": "洛白固定声音，没有就留空",
                  "must_split": false
                }
              ]
            }
            规则：
            - 角色和场景按剧本完整抽取；道具只允许从各场 props 数组引用，禁止从 action、heading、dialogue
              或环境描写中追加桌椅、杯子、衣物等普通物品。
            - props 已是筛选后的主要道具，按名称去重后逐条输出，不要扩写成零件或附属物。
            - 入梦 / 梦魇 / 换装 / 湿身等状态必须独立成条：name 用「本体名·状态」，
              state_tag 给 dream 或 costume，must_split 给 true，
              identity_invariants 写明「与本体同一张脸和发型；禁止写成另一个人」。
            - 状态条目绝不能并进本体 description，本体 description 里不得出现入梦/湿身字样。
            - 角色 / 场景 / 道具的 image_prompt 一律输出空字符串，由服务端按项目视觉风格生成完整设定图长句。
              禁止自行填写「浅灰背景的角色三视图」或任何短句。
            - 角色 identity_invariants 必须能把该人和其他角色区分开，写清：性别、年龄段、发型、脸型五官、身材、
              服装款式颜色与鞋履。三人不得共用同一套外貌；不要把男性写成女性，不要把中年写成少女。
            - 场景不要人物、不要整场剧情动作。道具只写外形、材质、磨损和标志性细节，不要剧情用途或人物名字。
            - description 与 identity_invariants 不得互相引用，不得写「同上」。
            - 长度上限：description 不超过 100 字，identity_invariants 不超过 80 字；image_prompt 一律留空。
            """;

    private static final String SCHEMA_SHOTS = """
            ShotDraftList 结构：
            {
              "chapters": [ { "index": 1, "name": "第一场", "scene_index": 1 } ],
              "shots": [
                {
                  "chapter_index": 1,
                  "index": 1,
                  "title": "侦探社开场",
                  "duration_sec": 15,
                  "narrative_goal": "全景。风扇转动，夏安往白板贴照片，洛白用书盖脸。",
                  "scene": "幻梦侦探社日戏，风扇与白板红线",
                  "dialogue": "局长：洛老弟，别睡了。\\n洛白：科学解决不了的，就轮到我们。",
                  "continuity_in": "风扇叶片入画",
                  "continuity_out": "洛白用书盖脸",
                  "character_names": ["局长", "洛白"],
                  "prop_names": ["白板"],
                  "location_name": "幻梦侦探社",
                  "cast_count": 2
                }
              ]
            }
            规则：
            - duration_sec 只许 5–15 的整数秒；按本镜对白长短、动作节拍各自估计，不要所有镜头填同一个秒数。
              时长只服务单镜，禁止为了凑总秒数而删戏或打乱时间线；不同场景不得并进一镜。
            - 必须按 scenes 的先后、场内 action 与 dialogue 的出现顺序拆镜；后一场的事件不得出现在前面的镜头里。
            - character_names / prop_names / location_name 只能原样引用给定资产名，不得发明新名。
            - character_names 只列本镜动作和对白里实际出现的人，不要把整场名单抄进每一镜。
            - character_names 必须按动作/对白里出场先后排序，不要按资产列表顺序。
            - 动作只写一个人时，character_names 只放这一个人。
            - 谁做动作就写谁的名字，不要把 A 的动作写到 B 身上。
            - prop_names 只列本镜被使用或被拿在手上的道具，不要每镜都带全场道具。
            - cast_count 必须等于 character_names 元素个数。
            - chapter_index 对应 scene_index；同一场允许只有 1 镜。地点、时间和可见状态连续时，
              能在 15 秒内完整表达的动作与短台词应优先合并，不要仅因景别变化或运镜变化拆镜。
            - 相邻镜头 continuity_out 与下一条 continuity_in 要接得上；写画面出入点
              （手上的道具、姿态、门的状态），不要写「环境立住」「对白落点」「切下一场」。
            - narrative_goal 写一个景别或主运镜，接着 2～3 个连续可见动作；15 秒内优先合并自然连续内容。
              不要写「交代关系」「推进对白」「关键道具出场」这类剧作句，也不能为减少镜头而遗漏情节。
            - scene 只写空间陈设与光线，不要写人物动作。
            - 不要写 compiled_prompt 或最终视频提示词，那是下游编译器的活。
            - dialogue（台词）优先完整保留：scenes[].dialogue 里的每一句，都要原样落到某条分镜的 dialogue 中
             （格式「说话人：原文」；同镜多句用换行分隔）。禁止摘要、改写、删半句、只留「关键句」或把台词揉进 narrative_goal。
              纯动作镜可留空字符串；有对白的节拍不得因压缩体积而丢掉台词。
            - 长度上限（仅压缩非台词字段）：narrative_goal 不超过 60 字（1 个景别/运镜 + 2~3 个动作关键词），
              scene 不超过 30 字，continuity_in / continuity_out 各不超过 20 字；dialogue 不受上述字数上限约束。
            """;

    private DramaTextPrompts() {
    }

    public static String scriptSystem() {
        return COMMON + SCHEMA_SCRIPT;
    }

    public static String scriptUser(String raw) {
        return "把下面这份剧本原文切成分场 ScriptDoc：\n\n" + raw;
    }

    /**
     * 分片分场：只解析本片段，服务端合并后重编号。
     */
    public static String scriptUserChunk(String raw, int chunkIndex, int chunkTotal) {
        return "把下面这份剧本原文片段切成分场 ScriptDoc。"
                + "这是第 " + chunkIndex + "/" + chunkTotal + " 片段；只输出本片段的 scenes，"
                + "index 从 1 连续递增即可（服务端会重编号）。"
                + "title、logline、style_prefix 仅在第 1 片段填写，后续片段可留空。\n\n"
                + raw;
    }

    public static String assetSystem() {
        return COMMON + SCHEMA_ASSETS;
    }

    public static String assetUser(String scriptJson) {
        return "从这份锁定剧本里抽资产清单 AssetDraftList：\n\n" + scriptJson;
    }

    public static String assetEnrichmentUser(String scriptJson, String skeletonJson) {
        return "为给定的确定性资产骨架补充描述字段，输出 AssetDraftList。\n"
                + "只能返回骨架中已有的资产，禁止新增、删除、改名或改类型；name、type、state_tag、must_split 必须逐字保持不变。\n"
                + "只补充 description、identity_invariants、voice_lock；image_prompt 一律输出空字符串，由服务端按项目风格生成完整设定图。\n"
                + "缺少信息时返回空字符串。\n\n"
                + "锁定剧本：\n" + scriptJson + "\n\n确定性资产骨架：\n" + skeletonJson;
    }

    public static String repairUser(String originalUser, String contractName, String reason, String previousOutput) {
        return """
                上一次结构化输出没有通过合同校验。请只修复格式或缺失字段，不要增删、改写剧情事实。
                输出合同：%s
                校验错误：%s

                原始任务：
                %s

                上一次输出：
                %s
                """.formatted(contractName, reason, clip(originalUser, 12000), clip(previousOutput, 10000));
    }

    private static String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public static String shotSystem() {
        return COMMON + SCHEMA_SHOTS;
    }

    public static String shotUser(String scriptJson, String assetNames) {
        return shotUser(scriptJson, assetNames, DramaContract.DEFAULT_DURATION_SEC, null);
    }

    public static String shotUser(String scriptJson, String assetNames, int durationSec) {
        return shotUser(scriptJson, assetNames, durationSec, null);
    }

    public static String shotUser(
            String scriptJson, String assetNames, int fallbackSec, Integer episodeBudgetSec) {
        return shotUserBody(scriptJson, assetNames, fallbackSec, episodeBudgetSec, null, null);
    }

    /**
     * 分片拆镜：只为本片段 scenes 出镜；chapter_index 对应该片段内 scene index。
     */
    public static String shotUserChunk(
            String scriptJson,
            String assetNames,
            int fallbackSec,
            Integer episodeBudgetSec,
            int chunkIndex,
            int chunkTotal) {
        return shotUserBody(scriptJson, assetNames, fallbackSec, episodeBudgetSec, chunkIndex, chunkTotal);
    }

    private static String shotUserBody(
            String scriptJson,
            String assetNames,
            int fallbackSec,
            Integer episodeBudgetSec,
            Integer chunkIndex,
            Integer chunkTotal) {
        StringBuilder text = new StringBuilder();
        text.append("为这份剧本出章节与分镜草稿 ShotDraftList。");
        if (chunkIndex != null && chunkTotal != null) {
            text.append("这是第 ").append(chunkIndex).append("/").append(chunkTotal)
                    .append(" 片段；只为本片段的 scenes 出镜，chapter_index 对应该片段内 scene index（从 1 起），")
                    .append("服务端会映射回全集场次号。");
        }
        text.append("只允许引用下列资产名，其它名字一律不得出现：\n").append(assetNames);
        text.append("\n硬性体积要求：只输出 ShotDraftList JSON，shots 必须是非空数组；");
        text.append("禁止输出思考过程、禁止在 JSON 外写解释；");
        text.append("可压缩 narrative_goal/scene/continuity 等非台词短句，单场通常 1～4 镜即可；");
        text.append("但 scenes 中的台词必须尽量原样保留到对应分镜 dialogue，禁止摘要或删句来省体积。");
        text.append("\n镜头必须严格按剧本场次先后、场内动作和对白出现顺序来排，禁止提前、延后或交叉无关事件。");
        text.append("\n每条分镜的 duration_sec 按该镜对白长短和动作节拍自行估计，只能是 5 到 15 的整数秒。");
        text.append("不要所有镜头填同一个秒数。无法判断时再用 ").append(fallbackSec).append(" 秒。");
        text.append("单镜时长不要影响镜头顺序，也不要为了凑总时长删戏或把两场戏并进一镜。");
        if (episodeBudgetSec != null && episodeBudgetSec > 0) {
            text.append("\n本集故事体量大约 ").append(episodeBudgetSec)
                    .append(" 秒，仅供感受戏有多长；不要把各镜秒数之和强行凑成这个数。");
        }
        text.append("\n镜头数量以最少且完整为目标：同一场景、时间和可见状态连续的内容，应在 15 秒内优先合并成一镜。");
        text.append("只有场景/时间跳转、状态无法连续、参考能力受限或完整表达超过 15 秒时才拆分；");
        text.append("不得为减少镜头而跳过情节，也不得为压缩体积而丢掉或改写台词。");
        text.append("\n\n剧本：\n").append(scriptJson);
        return text.toString();
    }
}
