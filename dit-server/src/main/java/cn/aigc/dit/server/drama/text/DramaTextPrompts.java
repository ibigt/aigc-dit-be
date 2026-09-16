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
                  "identity_invariants": "青年男性；清冷骨相；黑碎盖；修长；立领长风衣轮廓；锐利眼神",
                  "image_prompt": "浅灰或纯白背景的角色三视图：正面全身、侧面半身、面部特写",
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
            - 角色 image_prompt：中性浅灰或纯白背景的三视图参考图（正面全身、侧面半身、面部特写）；
              不要写剧情动作、首次出场、分镜格子、设定表排版或其它人物。
            - 场景 image_prompt：无人场景概念图，写空间陈设、材质与光线；不要人物、不要整场剧情动作。
            - 道具 image_prompt：只写该物件的外形、材质、磨损和标志性细节；静物产品级特写、纯色干净背景。
              不要写剧情用途、场面动作、人物名字或台词。
            - description 与 identity_invariants 不得互相引用，不得写「同上」。
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
                  "dialogue": "",
                  "continuity_in": "风扇叶片入画",
                  "continuity_out": "洛白用书盖脸",
                  "character_names": ["夏安", "洛白"],
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
            """;

    private DramaTextPrompts() {
    }

    public static String scriptSystem() {
        return COMMON + SCHEMA_SCRIPT;
    }

    public static String scriptUser(String raw) {
        return "把下面这份剧本原文切成分场 ScriptDoc：\n\n" + raw;
    }

    public static String assetSystem() {
        return COMMON + SCHEMA_ASSETS;
    }

    public static String assetUser(String scriptJson) {
        return "从这份锁定剧本里抽资产清单 AssetDraftList：\n\n" + scriptJson;
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
        StringBuilder text = new StringBuilder();
        text.append("为这份剧本出章节与分镜草稿 ShotDraftList。");
        text.append("只允许引用下列资产名，其它名字一律不得出现：\n").append(assetNames);
        text.append("\n镜头必须严格按剧本场次先后、场内动作和对白出现顺序来排，禁止提前、延后或交叉无关事件。");
        text.append("\n每条分镜的 duration_sec 按该镜对白长短和动作节拍自行估计，只能是 5 到 15 的整数秒。");
        text.append("不要所有镜头填同一个秒数。无法判断时再用 ").append(fallbackSec).append(" 秒。");
        text.append("单镜时长不要影响镜头顺序，也不要为了凑总时长删戏或把两场戏并进一镜。");
        if (episodeBudgetSec != null && episodeBudgetSec > 0) {
            text.append("\n本集故事体量大约 ").append(episodeBudgetSec)
                    .append(" 秒，仅供感受戏有多长；不要把各镜秒数之和强行凑成这个数。");
        }
        text.append("\n镜头数量以最少且完整为目标：同一场景、时间和可见状态连续的内容，应在 15 秒内优先合并成一镜。");
        text.append("只有场景/时间跳转、状态无法连续、参考能力受限或完整表达超过 15 秒时才拆分；不得为减少镜头而跳过情节。");
        text.append("\n\n剧本：\n").append(scriptJson);
        return text.toString();
    }
}
