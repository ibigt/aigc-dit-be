package cn.aigc.dit.server.drama.text;

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
            characters 收录本场出场与说话的所有人物；props 收录本场出现的具体道具；
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
                  "image_prompt": "中性纯色背景全身参考图，……",
                  "voice_lock": "洛白固定声音，没有就留空",
                  "must_split": false
                }
              ]
            }
            规则：
            - 角色、场景、道具三类都要出，剧本里出现过的名字一条都不能漏。
            - 入梦 / 梦魇 / 换装 / 湿身等状态必须独立成条：name 用「本体名·状态」，
              state_tag 给 dream 或 costume，must_split 给 true，
              identity_invariants 写明「与本体同一张脸和发型；禁止写成另一个人」。
            - 状态条目绝不能并进本体 description，本体 description 里不得出现入梦/湿身字样。
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
                  "duration_sec": 5,
                  "narrative_goal": "交代空间与二人关系，局长闯入",
                  "scene": "幻梦侦探社日戏，风扇与白板红线",
                  "dialogue": "局长：洛老弟，别睡了。",
                  "continuity_in": "开场",
                  "continuity_out": "洛白坐起掏表",
                  "character_names": ["洛白", "夏安", "局长"],
                  "prop_names": ["古铜怀表"],
                  "location_name": "幻梦侦探社",
                  "cast_count": 3
                }
              ]
            }
            规则：
            - duration_sec 只许 5、10、15。
            - character_names / prop_names / location_name 只能原样引用给定资产名，不得发明新名。
            - cast_count 必须等于 character_names 元素个数。
            - chapter_index 对应 scene_index，一场至少 2 镜；不要压成少数超长镜。
            - 相邻镜头 continuity_out 与下一条 continuity_in 要接得上。
            - 不要写 compiled_prompt、镜头语言套话或最终视频提示词，那是下游编译器的活。
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

    public static String shotSystem() {
        return COMMON + SCHEMA_SHOTS;
    }

    public static String shotUser(String scriptJson, String assetNames) {
        return "为这份剧本出章节与分镜草稿 ShotDraftList。"
                + "只允许引用下列资产名，其它名字一律不得出现：\n" + assetNames
                + "\n\n剧本：\n" + scriptJson;
    }
}
