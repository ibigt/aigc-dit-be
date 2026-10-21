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
                  "identity_invariants": "20岁，男，清冷骨相，黑色碎盖发，修长身形，深灰立领长风衣，黑色长裤，皮鞋，锐利眼神",
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
            - 角色 identity_invariants 是用户可见的外形设定，必须可区分角色，写清：性别、年龄或年龄段、
              发型发色、脸型五官、身材肤色、上装/外套/下装/鞋履（款式与颜色）。
              正例：「20岁，女，杏眼，鼻梁小巧，黑色短发刚过耳，暖黄肤色，白色圆领T恤，深蓝工装背心，深色长裤」。
              禁止只写「某某的外貌、发型、服装和年龄必须保持一致」这类空话；禁止三视图、设定图、风格前缀。
              三人不得共用同一套外貌；不要把男性写成女性，不要把中年写成少女。
            - 场景不要人物、不要整场剧情动作；每个场景都必须填写 identity_invariants：空间结构、陈设、材质与光影，
              正例：「高二教室，前后黑板，成排课桌椅，侧窗自然光，地面光洁」；禁止外貌/发型/服装/年龄，禁止「必须保持一致」空话。
            - 每个道具都必须填写 identity_invariants：外形、材质、磨损与标志性细节，
              正例：「黑色圆珠笔，细杆塑料笔身，金属笔尖，可手持特写」；禁止人物外貌与剧情用途，禁止空话。
            - description 与 identity_invariants 不得互相引用，不得写「同上」；角色 description 可与外形互补，但不得替代 identity_invariants。
            - 长度上限：description 不超过 100 字，identity_invariants 不超过 200 字；image_prompt 一律留空。
            """;

    private static final String SCHEMA_SHOTS = """
            ShotDraftList 结构：
            {
              "chapters": [ { "index": 1, "name": "第一场", "scene_index": 1 } ],
              "shots": [
                {
                  "chapter_index": 1,
                  "index": 1,
                  "title": "局长问责",
                  "duration_sec": 12,
                  "narrative_goal": "局长进门摔档案并问责。",
                  "scene": "局长办公室日戏，桌案与档案",
                  "dialogue": "局长（愤怒）：你给我站住！",
                  "shot_beats": {
                    "blocking": "局长在门口，洛白在画面左侧桌后，档案在桌上。",
                    "beats": [
                      {"camera": "中景，跟拍", "action": "局长推门进入。", "continuity": "门开", "dialogue": ""},
                      {"camera": "近景，下摇", "action": "局长把档案摔在桌上。", "continuity": "接推门进入", "dialogue": ""},
                      {"camera": "特写，静止", "action": "局长盯着洛白。", "continuity": "接档案落桌", "dialogue": "局长（愤怒）：你给我站住！"}
                    ]
                  },
                  "continuity_in": "门开",
                  "continuity_out": "档案落在桌上",
                  "character_names": ["局长", "洛白"],
                  "prop_names": ["档案"],
                  "location_name": "局长办公室",
                  "cast_count": 2
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
            - chapter_index 对应 scene_index。粒度优先少拆：同一场、同一空间里能在约 10–15 秒演完的连续内容尽量一条分镜；
              有戏份的场常见 1～3 镜，能一镜完成就不要拆成多镜。
              不要仅因景别、运镜、正反打偏好而拆镜。
              同场连续对谈（多人轮流说话）应落在同一条分镜，台词写进对应镜头；不要一句一镜。
              下列硬边界才拆开：地点或时间跳转；服装/身份状态突变；切到另一组无关人物或另一条动作线；
              或整段口播超过 duration_sec × 4 字、15 秒演不完（在镜头边界切开，不要为凑时长删戏）。
            - 一条分镜内部写 shot_beats，不因此新增分镜。blocking 是本镜起始站位（谁在画面左/右、门和关键道具在哪）。
              beats 写 1–3 个镜头，用来换景别和下一拍动作。每个镜头含 camera（一个景别与运镜）、
              action（这一拍能拍出来的动作链：起势、动作、落点。同一角色的连续动作用逗号连在一句里，换一个人才断开。40～120 字。不写台词，不编造原稿没有的情节）、
              continuity（接上一镜头的哪一个动作）、dialogue（可空，只放这一镜头要说的那一句）。
              只有一拍时就写 1 个镜头。景别变化写进这些镜头，不要为此新开分镜。
              同一条分镜内相邻镜头的景别或机位必须有变化（如中景接近景、推近接横移、正面接侧面），
              禁止每镜都写同一个景别。
              全景或远景只用于一场里交代空间的开场镜头。对白、问责、争吵用中近景或过肩，拍正在说话的人，
              不要用全景把所有人同时摆进画面。没开口的人留在画外或只出肩背。
            - narrative_goal 只写一句摘要给分镜卡片，不要把景别和整段动作链写进去。
              不要写「交代关系」「推进对白」「关键道具出场」这类剧作句，也不能为减少镜头而遗漏情节。
            - 相邻分镜 continuity_out 与下一条 continuity_in 要接得上；写画面出入点
              （手上的道具、姿态、门的状态），不要写「环境立住」「对白落点」「切下一场」。
            - 同场且下一条分镜仍要画面衔接时，本条 shot_beats 的最后一拍 camera
              优先用中景、近景或特写，不要用全景、远景或大幅度推拉摇移收尾，
              方便下一镜从中近景起幅承接；场内唯一一条分镜或明确硬切换场时不强制。
            - 同一场内，相邻分镜里同一个人物在画面中的相对站位要保持稳定
              （左就在左、靠窗就在靠窗、坐在桌子同一侧就在同一侧），
              只能在动作或对白明确让人物位移时才改站位；禁止无理由地「忽左忽右」。
              如果一镜里写成「洛白在画面左侧」，下一镜再出现洛白时默认仍在左侧。
            - scene 只写空间陈设与光线，不要写人物动作。
            - 不要写 compiled_prompt 或最终视频提示词，那是下游编译器的活。
            - dialogue（台词）优先完整保留：scenes[].dialogue 里的每一句，都要原样落到对应镜头的 dialogue，
              并按镜头顺序用换行拼进该分镜的 dialogue。不要把整段案情堆在最后一个镜头。
              有 emotion 时格式「说话人（情绪）：原文」；无 emotion 时「说话人：原文」；旁白一律「旁白：原文」（不要写情绪括号）。
              角色的画外音/内心独白写成「说话人（画外音）：原文」，括号固定写「画外音」，不再叠情绪括号；
              speaker 为「角色名（画外音）」的照原样保留该标记，禁止改写成「旁白」，发声角色也应列入 character_names。
              禁止摘要、改写、删半句、只留「关键句」或把台词揉进 action / narrative_goal。
              纯动作镜头的 dialogue 留空字符串；有对白的节拍不得因压缩体积而丢掉台词。
            - 长度上限（仅压缩非台词字段）：narrative_goal 不超过 40 字，每个镜头 action 40～120 字，
              scene 不超过 30 字，continuity_in / continuity_out 与每个镜头 continuity 各不超过 20 字；
              dialogue 不受上述字数上限约束。
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
                + "角色的 identity_invariants 是产品主展示字段：必须写出性别、年龄/年龄段、长相（发型五官肤色身材）与衣着"
                + "（上装/外套/下装/鞋履），可据剧情合理推断；禁止输出「外貌、发型、服装和年龄必须保持一致」空话，"
                + "禁止写三视图/设定图/风格前缀。\n"
                + "每个场景都必须填写 identity_invariants：空间结构、陈设、材质与光影，"
                + "正例「高二教室，前后黑板，成排课桌椅，侧窗自然光」；禁止外貌/发型/服装/年龄，禁止「必须保持一致」空话。\n"
                + "每个道具都必须填写 identity_invariants：外形、材质、磨损与标志性细节，"
                + "正例「黑色圆珠笔，细杆塑料笔身，金属笔尖」；禁止人物外貌与剧情用途，禁止空话。\n"
                + "角色缺少信息时可留空；场景和道具尽量据剧情合理推断，不要整批留空。\n\n"
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
        text.append("同场各镜 title 按节拍区分、不要共用一个场名；一条分镜内部相邻镜头景别或机位要有变化，写进 shot_beats，不要因此多拆一条分镜。");
        text.append("同场且下一条仍要画面衔接时，本条最后一拍 camera 优先中景、近景或特写，不要用全景或大幅度运镜收尾。");
        text.append("\n每条分镜的 duration_sec 优先估在 10 到 15 秒；内容本身很短才用 5 到 9 秒。只能是 5 到 15 的整数秒。");
        text.append("台词按每秒 4 字折算（只数口播字，标点符号不发音、不计数）：台词写进对应镜头的 dialogue，不要把整段案情堆在最后一个镜头。");
        text.append("整段口播超过 duration_sec × 4 字、或 15 秒演不完时，才在镜头边界再拆一条分镜。");
        text.append("不要所有镜头填同一个秒数。无法判断时再用 ").append(preferredFallback(fallbackSec)).append(" 秒。");
        text.append("单镜时长不要影响镜头顺序，也不要为了凑总时长删戏或把两场戏并进一镜。");
        if (episodeBudgetSec != null && episodeBudgetSec > 0) {
            text.append("\n本集故事体量大约 ").append(episodeBudgetSec)
                    .append(" 秒，仅供感受戏有多长；不要把各镜秒数之和强行凑成这个数。");
        }
        text.append("\n镜头粒度优先少拆：同场同空间、约 10–15 秒能演完的连续内容尽量一条分镜。");
        text.append("一条分镜内部写 1 到 3 个镜头（shot_beats.beats），用来换景别和下一拍动作；能一镜演完就不要拆成多条分镜。");
        text.append("同场连续对谈（多人轮流说话）放同一条分镜，不要一句一条分镜或一轮一对白一条分镜。");
        text.append("不要仅因景别、运镜、正反打而拆镜。");
        text.append("只有地点/时间跳转、状态突变、切到无关人物或另一条动作线、或超过 15 秒演不完时才在镜头边界再拆一条分镜。");
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
     * 分镜视觉扩写：输入已拆好的草稿，把画面拆成 scene / camera / narrative_goal / ambiance。
     * narrative_goal 只留动作。独立于 COMMON——扩写要的是可拍摄的细节，不吃「尽量精简」那条。
     */
    private static final String SYSTEM_SHOT_ENRICH = """
            你是漫剧流水线的「分镜视觉扩写层」。输入是拆好的分镜草稿（含场次上下文、台词、时长与人物名单），
            任务是把每条分镜扩写成可直接指导视频生成的画面，其余一律不做。
            硬性要求：
            1. 只输出一个 JSON 对象：
               {"shots":[{"chapter_index":N,"index":M,"scene":"…","camera":"…","narrative_goal":"…","ambiance":"…"}]}；
               不要 Markdown 围栏、不要解释文字；shots 必须逐条覆盖输入的每一条分镜，
               chapter_index 与 index 与输入完全一致，键名 snake_case 不得增删改名。
            2. 三不动：
               - 人物不能多、不能少、不能换人：原稿 narrative_goal 里出现的人物名必须原样写进新的 narrative_goal；
                 本镜没有的角色一律不得出现。
               - 台词一个字不许动，也不许把台词、旁白、对白内容写进 scene、camera、narrative_goal 或 ambiance。
               - 谁做什么动作不许变：动作的主语与内容保持原样，只允许补充可拍摄的动作细节。
            3. 四个字段分开写，不要互相复述：
               - camera：景别、构图、机位和运镜落点写在一起，如「中景，中心构图，微仰，缓慢推近到面部与袖口」。16～40 字。
                 禁止只写「缓慢推近」或「中景，静止」；运镜要写从哪推到哪个细节。
                 相邻分镜的景别或机位要有变化（前一镜中景推近，下一镜可换近景、特写或侧面），
                 不要每镜都以「中景」开头。
                 同场且下一条分镜仍要画面衔接时，本条最后一拍（有 beats 时写最后一条 camera；
                 无 beats 时写本条 camera）优先中景、近景或特写，不要用全景、远景或大幅度运镜收尾。
               - narrative_goal：仅当该分镜没有 beats 时才写。只写该时长内的运动。不写景别、不写运镜、不写光线、不写空间陈设、不写服装长相。
                 按主体动作、物件互动、环境动态写成连贯叙述；60～150 字。
                 剧本里已有的压抑、反差、藏道具，写成眼神、嘴角、手部、袖中物件这些能拍到的细节，不要写成「氛围感」「命运感」。
                 动作可以「平静回应」「低声问」「冷声道」收尾，不要每次都写成「说」。
                 非口播不要写成冒号紧挨引号，牌匾、字条和标题不要用冒号加引号。
                 说话人是画外音时，画面只写在场者的反应，并写「只闻其声，不出现说话人实体」。
                 动作量跟本镜 duration_sec：5～7 秒完成一个连贯动作加一个细节；
                 8～11 秒可完成一次前后相接的过渡（如抬头、对视、开口前的停顿）；
                 12～15 秒保持一条先后相接的动作链（如起身、走到窗前、驻足），不要同时做多件无关的事。
                 该分镜已有 beats 时不要输出 narrative_goal，禁止把多个镜头收成这一段。
               - ambiance：只写画内能听到的声音，如梵音、衣袍摩擦、殿内回声、风扇低鸣、纸页轻响、脚步。不要 BGM、配乐、旁白、台词，并写明不要配乐。8～40 字。没有明显环境声就留空字符串。
               - scene：只写空间陈设、材质质感与光源氛围，并补一句主色和光质（如「金色光柱」「墨黑轮廓」「日光从百叶窗斜切进来，浮尘漂浮，桌面堆着牛皮档案」），
                 不写人物动作，不写长相；40～90 字。
            4. 一帧只有一个时空。原稿若是回忆、闪回或内心活动，改写成此刻看得见的神态、手里的物件和环境痕迹，不要切到另一段时空。
               正例：原稿「她想起亡母临终的嘱托」→ narrative_goal「她跪坐在灵位前，指尖抵着一串磨亮的佛珠，眼睑低垂」；
               scene「案上白烛烧过半，烛泪堆在烛台边，侧脸有一块摇晃的暖橙光」。
               反例：「她陷入多年前那个绝望的雨夜，画面基调忧郁」——另一个时空，而且「忧郁」不是看得见的东西。
               反例：「她坐着发呆」——没有环境、光线和可拍摄的动作。
            5. 光线氛围必须符合所在场次的 time（日/夜）与 interior（内/外）：
               夜戏写夜景光源，外景写天光，不得把夜场写成白天。
               若用户消息给了项目视觉风格，陈设、器物和年代必须贴合这句风格，
               不得引入另一种时代的东西（赛博朋克镜里不要榻榻米和古装窗棂，民国镜里不要霓虹屏和义体）。
            6. 人物长相、五官、发型与服装款式不要描写（由角色设定图锚定），可写姿态、位置与朝向。不另起角色小传。
            7. 人名、道具名、地点名原样沿用输入写法，不得简称、改写或翻译。
            8. 四个字段都不要使用这些祈使：增加、删除、去掉、修改、替换、改成、延长、续写。
               直接写画面里正在发生的动作。不写「把书改成盖在脸上」，写「双手把书压在脸上」。
            9. 输入该条已有 beats 时，改为逐镜头补可拍摄细节，不要把多个镜头收成一段 narrative_goal。
               输出 blocking 与 beats，条数和顺序与输入一致，每条只含 camera、action、continuity。
               每个镜头的 action 写成这一拍能拍出来的动作链：起势、动作、落点，40～120 字。
               同一角色的连续动作用逗号连在一起，换一个人才断开。
               剧本里已有的压抑、反差、藏道具，写成眼神、嘴角、手部、袖中物件，不要写成「氛围感」「命运感」。
               动作可以「平静回应」「低声问」「冷声道」收尾，不要每次都写成「说」。
               非口播不要写成冒号紧挨引号。
               说话人是画外音时，画面只写在场者的反应，并写「只闻其声，不出现说话人实体」。
               人物名必须仍能对上该镜头原稿 action；台词不写进 action；不要编造原稿没有的情节。
               景别写在各镜头 camera。没有 beats 的旧草稿继续用上面的 scene / camera / narrative_goal / ambiance。
            """;

    public static String shotEnrichSystem() {
        return SYSTEM_SHOT_ENRICH;
    }

    public static String shotEnrichUser(String scenesJson, String shotsJson) {
        return shotEnrichUser(scenesJson, shotsJson, null);
    }

    public static String shotEnrichUser(String scenesJson, String shotsJson, String visualStyle) {
        StringBuilder text = new StringBuilder();
        if (visualStyle != null && !visualStyle.isBlank()) {
            text.append("项目视觉风格：").append(visualStyle.strip()).append('\n');
            text.append("补环境、器物和年代时必须贴合这句风格，不得引入另一种时代的陈设。\n\n");
        }
        text.append("场次上下文（chapter_index 对应的场次、日夜与内外景）：\n").append(scenesJson);
        text.append("\n\n待扩写分镜草稿：\n").append(shotsJson);
        text.append("\n\n逐条输出扩写后的 scene、camera、narrative_goal 与 ambiance；");
        text.append("已有 beats 的分镜改为逐镜头输出 blocking 与 beats，不要收成一句 narrative_goal。");
        text.append("chapter_index 与 index 必须与输入一致，输入有几条就输出几条，不要合并或遗漏。");
        text.append("没有 beats 时 narrative_goal 只写动作，景别和运镜写进 camera，画内声写进 ambiance。");
        text.append("camera 要写出构图、机位，以及从哪推到哪个细节。");
        text.append("scene 补主色和光质。画外音只写在场者的反应，并写只闻其声、不出现说话人实体。");
        return text.toString();
    }
}
