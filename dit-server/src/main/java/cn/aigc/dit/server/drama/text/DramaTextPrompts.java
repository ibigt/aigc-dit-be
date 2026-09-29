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
                    { "speaker": "局长", "line": "你给我站住！", "emotion": "愤怒" },
                    { "speaker": "洛白", "line": "找猫抓狗去居委会。但这案子……", "emotion": "" },
                    { "speaker": "旁白", "line": "夜色渐深。", "emotion": "" }
                  ]
                }
              ]
            }
            index 从 1 连续递增；interior 内景为 true、外景为 false；
            characters 只收录本场真实出场人物，禁止收录旁白/画外音/解说/VO/OS 等非人物说话人；
            props 只收录主要道具，并按剧情重要性从高到低排列，每场最多 3 个：
            - 保留推动情节、触发关键动作/冲突、承载线索，或能稳定识别角色身份的道具；
            - 普通家具、环境陈设、服装、餐具、文具、消耗品及仅在背景中一闪而过的物品不要收录；
            - 同一道具不要重复；没有主要道具时返回空数组，不要为了凑数添加物品；
            action 只写可视动作与环境，不要写心理独白，不要把台词搬进来。
            - action 控制在 80 字以内，只写可视动作与环境的最小集合。
            - sound 写 1~2 个关键声音关键词，总长不超过 20 字。
            - dialogue 严格保留剧本原文，不要扩写、解释或补全台词。
            - dialogue.speaker 若是旁白/画外音/解说/VO/OS/旁白音 等纯第三人称解说，一律输出「旁白」；旁白只出现在 dialogue.speaker，不得进 characters。
            - 台词若是某个角色的画外音或内心独白（用该角色的声音说、画面中人物不开口），speaker 写「角色名（画外音）」，
              不要归成「旁白」；该角色照常进 characters。
            - dialogue.emotion 闭集：高兴/愤怒/悲伤/恐惧/惊讶/厌恶/紧张；只标原文或舞台提示已能看出的情绪（如「怒道」「哭着说」），看不出则给空字符串；旁白句 emotion 一律空字符串；非法值不要输出。
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
                  "title": "侦探社对谈",
                  "duration_sec": 12,
                  "narrative_goal": "中景。夏安贴照片，局长叫醒洛白，洛白拿开盖脸的书。",
                  "scene": "幻梦侦探社日戏，风扇与白板红线",
                  "dialogue": "局长（愤怒）：你给我站住！\\n洛白：科学解决不了的，就轮到我们。\\n旁白：夜色渐深。",
                  "continuity_in": "风扇叶片入画",
                  "continuity_out": "洛白书拿开",
                  "character_names": ["夏安", "局长", "洛白"],
                  "prop_names": ["白板"],
                  "location_name": "幻梦侦探社",
                  "cast_count": 3
                }
              ]
            }
            规则：
            - duration_sec 只许 5–15 的整数秒；优先估在 10–15 秒。内容本身很短才用 5–9 秒。
              中文台词按每秒 4 字折算口播时长（只数汉字等口播字，标点符号不发音、不计数）：
              本镜 dialogue 总口播字数超过 duration_sec × 4 就念不完，
              必须按对白句边界拆成多镜；动作镜按节拍估。
              按本镜对白长短、动作节拍估计，不要所有镜头填同一个秒数。
              时长只服务单镜，禁止为了凑总秒数而删戏或打乱时间线；不同场景不得并进一镜。
            - 必须按 scenes 的先后、场内 action 与 dialogue 的出现顺序拆镜；后一场的事件不得出现在前面的镜头里。
            - 动作只归它发生的那一镜：narrative_goal 只写本镜时间点内发生的动作，
              禁止把整场动作链复述进每一镜，也禁止后续镜头复演先前镜头已演过的动作
              （拍桌、起身、拔U盘这类动作全场只演一次）。
            - title 概括本镜节拍而非场名（如「张总问责」「决意赴机房」）；同一场内各镜标题不得雷同。
            - character_names / prop_names / location_name 只能原样引用给定资产名，不得发明新名。
            - character_names 只列本镜动作和对白里实际出现的人，不要把整场名单抄进每一镜；禁止列入旁白/画外音/解说。
            - character_names 必须按动作/对白里出场先后排序，不要按资产列表顺序。
            - 同场连续对谈里出现的说话人都应列入 character_names，不要只留当前开口的那一个人。
            - 谁做动作就写谁的名字，不要把 A 的动作写到 B 身上。
            - prop_names 只列本镜被使用或被拿在手上的道具，不要每镜都带全场道具。
            - cast_count 必须等于 character_names 元素个数。
            - chapter_index 对应 scene_index。粒度优先少拆：同一场、同一空间里能在约 10–15 秒演完的连续内容尽量一镜；
              有戏份的场常见 1～3 镜，能一镜完成就不要拆成多镜。
              不要仅因景别、运镜、正反打偏好而拆镜。
              同场连续对谈（多人轮流说话）应落在同一镜，dialogue 用换行分隔多句；不要一句一镜。
              下列硬边界才拆开：地点或时间跳转；服装/身份状态突变；切到另一组无关人物或另一条动作线；
              或完整内容超过 15 秒演不完（台词按每秒 4 字折算；在对白句或动作句边界切开，不要为凑时长删戏）。
            - 相邻镜头 continuity_out 与下一条 continuity_in 要接得上；写画面出入点
              （手上的道具、姿态、门的状态），不要写「环境立住」「对白落点」「切下一场」。
            - 同一场内，相邻分镜里同一个人物在画面中的相对站位要保持稳定
              （左就在左、靠窗就在靠窗、坐在桌子同一侧就在同一侧），
              只能在动作或对白明确让人物位移时才改站位；禁止无理由地「忽左忽右」。
              如果一镜里写成「洛白在画面左侧」，下一镜再出现洛白时默认仍在左侧。
            - narrative_goal 写一个景别或主运镜，接着本镜主要可见动作；对谈镜可概括交谈双方动作，不要写成一句一镜。
              同一场内相邻镜头的景别或机位必须有变化（如中景接近景、推近接横移、正面接侧面），
              禁止每镜都写同一个景别。
              不要写「交代关系」「推进对白」「关键道具出场」这类剧作句，也不能为减少镜头而遗漏情节。
            - scene 只写空间陈设与光线，不要写人物动作。
            - 不要写 compiled_prompt 或最终视频提示词，那是下游编译器的活。
            - dialogue（台词）优先完整保留：scenes[].dialogue 里的每一句，都要原样落到某条分镜的 dialogue 中。
              有 emotion 时格式「说话人（情绪）：原文」；无 emotion 时「说话人：原文」；旁白一律「旁白：原文」（不要写情绪括号）。
              角色的画外音/内心独白写成「说话人（画外音）：原文」，括号固定写「画外音」，不再叠情绪括号；
              speaker 为「角色名（画外音）」的照原样保留该标记，禁止改写成「旁白」，发声角色也应列入 character_names。
              同镜多句用换行分隔。禁止摘要、改写、删半句、只留「关键句」或把台词揉进 narrative_goal。
              纯动作镜可留空字符串；有对白的节拍不得因压缩体积而丢掉台词。
            - 长度上限（仅压缩非台词字段）：narrative_goal 不超过 60 字，
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
        text.append("可压缩 narrative_goal/scene/continuity 等非台词短句，有戏份的单场常见 1～3 镜，能一镜演完就不要多拆；");
        text.append("但 scenes 中的台词必须尽量原样保留到对应分镜 dialogue，禁止摘要或删句来省体积。");
        text.append("\n镜头必须严格按剧本场次先后、场内动作和对白出现顺序来排，禁止提前、延后或交叉无关事件。");
        text.append("\n动作只归它发生的那一镜：每镜只写本镜时间点内发生的动作，后续镜禁止复演先前镜已演过的动作；");
        text.append("同场各镜 title 按节拍区分、不要共用一个场名；相邻镜头景别或机位要有变化。");
        text.append("\n每条分镜的 duration_sec 优先估在 10 到 15 秒；内容本身很短才用 5 到 9 秒。只能是 5 到 15 的整数秒。");
        text.append("台词按每秒 4 字折算（只数口播字，标点符号不发音、不计数）：一镜 dialogue 总口播字数超过 duration_sec × 4 就念不完，要在对白句边界拆成多镜，不要把整场对谈塞进一镜。");
        text.append("不要所有镜头填同一个秒数。无法判断时再用 ").append(preferredFallback(fallbackSec)).append(" 秒。");
        text.append("单镜时长不要影响镜头顺序，也不要为了凑总时长删戏或把两场戏并进一镜。");
        if (episodeBudgetSec != null && episodeBudgetSec > 0) {
            text.append("\n本集故事体量大约 ").append(episodeBudgetSec)
                    .append(" 秒，仅供感受戏有多长；不要把各镜秒数之和强行凑成这个数。");
        }
        text.append("\n镜头粒度优先少拆：同场同空间、约 10–15 秒能演完的连续内容尽量一镜。");
        text.append("同场连续对谈（多人轮流说话）放同一镜，不要一句一镜或一轮一对白一镜。");
        text.append("不要仅因景别、运镜、正反打而拆镜。");
        text.append("只有地点/时间跳转、状态突变、切到无关人物或另一条动作线、或超过 15 秒演不完时才拆开。");
        text.append("不得为减少镜头而跳过情节，也不得为压缩体积而丢掉或改写台词。");
        text.append("\n\n剧本：\n").append(scriptJson);
        return text.toString();
    }

    /** 无法估时长时的兜底，偏向 10–15 偏好区间。 */
    static int preferredFallback(int fallbackSec) {
        int value = fallbackSec < 5 ? DramaContract.DEFAULT_DURATION_SEC : fallbackSec;
        if (value < 10) {
            return 10;
        }
        return Math.min(15, value);
    }

    /**
     * 分镜视觉扩写：输入已拆好的草稿（含台词与人物名单），只把 narrative_goal / scene
     * 两个字段扩成富画面描述。独立于 COMMON——扩写要的是丰富，不吃「尽量精简」那条。
     */
    private static final String SYSTEM_SHOT_ENRICH = """
            你是漫剧流水线的「分镜视觉扩写层」。输入是拆好的分镜草稿（含场次上下文、台词与人物名单），
            任务是把每条分镜的 narrative_goal 与 scene 扩写成可直接指导视频生成的画面描述，其余一律不做。
            硬性要求：
            1. 只输出一个 JSON 对象：{"shots":[{"chapter_index":N,"index":M,"scene":"…","narrative_goal":"…"}]}；
               不要 Markdown 围栏、不要解释文字；shots 必须逐条覆盖输入的每一条分镜，
               chapter_index 与 index 与输入完全一致，键名 snake_case 不得增删改名。
            2. 三不动：
               - 人物不能多、不能少、不能换人：原稿 narrative_goal 里出现的人物名必须原样保留；
                 本镜没有的角色一律不得出现。
               - 台词一个字不许动，也不许把台词、旁白、对白内容写进 narrative_goal 或 scene。
               - 谁做什么动作不许变：动作的主语与内容保持原样，只允许补充画面细节。
            3. 只补充镜头语言与画面质感：
               - narrative_goal 以景别与主运镜开头（如「中景，缓慢推近」），按原动作顺序串联动作链，
                 补充光线方向、环境氛围与人物站位朝向；60～150 字；不写心理独白与剧作术语。
               - 相邻分镜的景别或机位要有变化（前一镜中景推近，下一镜可换近景、特写或侧面），
                 不要每镜都以「中景」开头。
               - scene 只写空间陈设、材质质感与光源氛围（如「日光从百叶窗斜切进来，浮尘漂浮，
                 桌面堆着牛皮档案」），不写人物动作；40～90 字。
            4. 光线氛围必须符合所在场次的 time（日/夜）与 interior（内/外）：
               夜戏写夜景光源，外景写天光，不得把夜场写成白天。
            5. 人物长相、五官、发型与服装款式不要描写（由角色设定图锚定），
               可写姿态、位置与朝向。
            6. 人名、道具名、地点名原样沿用输入写法，不得简称、改写或翻译。
            """;

    public static String shotEnrichSystem() {
        return SYSTEM_SHOT_ENRICH;
    }

    public static String shotEnrichUser(String scenesJson, String shotsJson) {
        return "场次上下文（chapter_index 对应的场次、日夜与内外景）：\n" + scenesJson
                + "\n\n待扩写分镜草稿：\n" + shotsJson
                + "\n\n逐条输出扩写后的 scene 与 narrative_goal；chapter_index 与 index 必须与输入一致，"
                + "输入有几条就输出几条，不要合并或遗漏。";
    }
}
