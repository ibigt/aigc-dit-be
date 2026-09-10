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

    private DramaTextPrompts() {
    }

    public static String scriptSystem() {
        return COMMON + SCHEMA_SCRIPT;
    }

    public static String scriptUser(String raw) {
        return "把下面这份剧本原文切成分场 ScriptDoc：\n\n" + raw;
    }
}
