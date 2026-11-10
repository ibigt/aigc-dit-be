-- 短剧剧本「深度解析」v2：中间态表、分析心跳、engine 允许 v2-agent，并写入当前生效的 prompt。
-- 正文取 merge 最终 SQL（与 DramaTextPrompts 的 v2 fallback 一致），不回放历史版本。
-- 脚本可重复执行：建表 / 加列用 IF NOT EXISTS，约束先删后加，prompt 按 (key, version) 冲突跳过。

CREATE TABLE IF NOT EXISTS script_prompt (
  id BIGSERIAL PRIMARY KEY,
  key VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT true,
  attribution VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE(key, version)
);

CREATE INDEX IF NOT EXISTS idx_script_prompt_active ON script_prompt(key) WHERE active;

COMMENT ON TABLE script_prompt IS '剧本生成 Prompt 版本库（覆盖 DramaTextPrompts 的 v2 fallback）';
COMMENT ON COLUMN script_prompt.key IS 'prompt 标识：common / schema_skeleton / schema_script / ...';
COMMENT ON COLUMN script_prompt.attribution IS '归属：drama-text / script-creation / outline / supervisor';
COMMENT ON COLUMN script_prompt.active IS '当前生效版本（每个 key 仅一条 active=true）';

CREATE TABLE IF NOT EXISTS drama_plan_data (
  id BIGSERIAL PRIMARY KEY,
  episode_id BIGINT NOT NULL REFERENCES drama_episode(id) ON DELETE CASCADE,
  key VARCHAR(64) NOT NULL,
  data JSONB NOT NULL,
  engine VARCHAR(32),
  error TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE(episode_id, key)
);

CREATE INDEX IF NOT EXISTS idx_drama_plan_data_episode ON drama_plan_data(episode_id);

COMMENT ON TABLE drama_plan_data IS '剧本解析中间态（skeleton / script_v2 / asset_draft / shot_draft）';
COMMENT ON COLUMN drama_plan_data.key IS '阶段标识：skeleton / script_v2 / asset_draft / shot_draft';
COMMENT ON COLUMN drama_plan_data.engine IS '生成引擎：llm / rules / llm+repair / llm+supervisor / llm+rules+supervisor';

CREATE TABLE IF NOT EXISTS drama_memory (
  id BIGSERIAL PRIMARY KEY,
  series_id BIGINT NOT NULL REFERENCES drama_series(id) ON DELETE CASCADE,
  type VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  source_episode_id BIGINT REFERENCES drama_episode(id) ON DELETE SET NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE(series_id, type)
);

CREATE INDEX IF NOT EXISTS idx_drama_memory_series ON drama_memory(series_id);

COMMENT ON TABLE drama_memory IS '项目级记忆：characters / world / user_pref / style_pref';
COMMENT ON COLUMN drama_memory.type IS '记忆类型：characters / world / user_pref / style_pref';

CREATE TABLE IF NOT EXISTS agent_run (
  id BIGSERIAL PRIMARY KEY,
  series_id BIGINT NOT NULL,
  episode_id BIGINT NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  supervisor_type VARCHAR(32),
  session_id VARCHAR(64) NOT NULL,
  prompt_key VARCHAR(64),
  prompt_version INTEGER,
  model VARCHAR(128),
  input_hash VARCHAR(64),
  output_hash VARCHAR(64),
  success BOOLEAN NOT NULL,
  supervisor_pass BOOLEAN,
  supervisor_repaired BOOLEAN NOT NULL DEFAULT false,
  supervisor_issues JSONB,
  retry_count INTEGER NOT NULL DEFAULT 0,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  error TEXT,
  started_at TIMESTAMP NOT NULL,
  finished_at TIMESTAMP,
  duration_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_agent_run_episode ON agent_run(episode_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_agent_type ON agent_run(agent_type, success);
CREATE INDEX IF NOT EXISTS idx_agent_run_prompt_version ON agent_run(prompt_key, prompt_version);

COMMENT ON TABLE agent_run IS 'Agent 运行日志：成功 / supervisor 通过 / 重试次数 / token 消耗';
COMMENT ON COLUMN agent_run.agent_type IS 'agent 类型：skeleton / script / assets / shots';
COMMENT ON COLUMN agent_run.supervisor_pass IS 'supervisor 校验是否通过（null = 未跑 supervisor）';

ALTER TABLE drama_episode
    ADD COLUMN IF NOT EXISTS script_analyzing_heartbeat_at TIMESTAMPTZ;

COMMENT ON COLUMN drama_episode.script_analyzing_heartbeat_at
    IS '一键分析剧本的心跳；分析期间每分钟续期。超时视为僵死可重试';

ALTER TABLE drama_episode DROP CONSTRAINT IF EXISTS ck_drama_episode_engine;
ALTER TABLE drama_episode ADD CONSTRAINT ck_drama_episode_engine
    CHECK (engine IN ('pending', 'rules', 'llm', 'v2-agent'));

COMMENT ON COLUMN drama_episode.engine
    IS '分场引擎：pending 尚未用文本模型成功解析；rules 仅兼容旧数据；llm 文本模型；v2-agent 深度解析';

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'common',
  $v63_common$你是漫剧流水线的「剧本理解层」，只做文本进、结构化 JSON 出。
硬性要求：
1. 只输出一个 JSON 对象，不要 Markdown 代码围栏，不要解释文字。
2. 键名必须与给定 schema 完全一致，一律 snake_case，不得增删改名。
3. 字符串字段无内容时给空字符串，数组字段无内容时给空数组，不要给 null。
4. 人物、道具、地点名必须原样沿用剧本里出现的写法，不要改写、不要简称、不要合并同义称呼。
5. 剧本里没有的信息不要发明；但资产的外形设定（identity_invariants）与场景、道具设定属于创作补全：
   剧本没写明的部分由你按人物身份合理设计，写成具体、可区分、可编辑的设定，禁止留空。
6. 字数压缩只在 schema 显式声明的字段生效（如 style_prefix、logline、hook 用关键词即可）；
   scene.action、scene.dialogue、scene.characters、characters_recurrent、dialogue.speaker、dialogue.line
   这类保留字段按原文需要写够，不要自行精简、不要合并相邻动作、不要把多句台词并成一句。
$v63_common$,
  1,
  true,
  'drama-text',
  '公共头：资产外形/场景/道具设定允许创作补全，禁止留空'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'schema_skeleton',
  $v63_schema_skeleton$SkeletonDoc 结构：
{
  "logline": "一句话故事梗概",
  "style_prefix": "风格短句",
  "episodes": [
    {
      "index": 1,
      "title": "第 1 集",
      "logline": "本集一句话",
      "hook": "本集开场钩子",
      "characters": ["主要角色"],
      "scenes_hint": [{"heading": "幻梦侦探社", "purpose": "主场景"}],
      "climax": "本集高潮",
      "next_episode_tease": "下集预告"
    }
  ],
  "characters_recurrent": ["跨集复用的核心人物"],
  "world_rules": ["世界观规则，最多 5 条"]
}
规则：
- 集数等于 episodes.length，必须严格匹配 targetEpisodes
- characters_recurrent 必须是规范姓名字符串数组：每项就是一个角色本名，
  与下游 dialogue.speaker 的写法完全一致。
  禁止加「：性别/年龄/外貌」之类后缀；禁止写「未描述」或任何占位字面量；
  禁止写分组标签、集合称谓、代词或空字符串。
  不知道本名时把这一项从数组里去掉，不要靠补充描述伪装成人物。
- episodes[].characters 才是允许在名字后追加「：性别/年龄段/核心外貌」一行式描述的字段；
  同一角色的名字本名必须与 characters_recurrent 完全一致，方便下游跨集对齐。
- scenes_hint 控制在 6 条以内；原文有明确场次标题（「第N场」「【场景 N】」等）时，
  按原文实际场数给 scenes_hint（最多 12 条），不要压到 6 条以内
- world_rules 控制在 5 条以内
$v63_schema_skeleton$,
  1,
  true,
  'outline',
  '故事骨架 Schema：原文有场次标记时 scenes_hint 按原文实际场数给（最多 12 条）'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'schema_script',
  $v63_schema_script$ScriptDoc 结构：
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
location 只写一个场地名。原文同一场里出现多个场地（「甲，乙」「甲、乙」「甲和乙」）时，
  把这一场拆成多场，每场只写一个场地，动作与台词按原文出现顺序分到对应场地的那一场；
  不要把多个场地并列写进同一个 location —— 场景资产按 location 抽取，
  并列的 location 会让同一个地方被抽成两张场景设定图。
原文里有明确场次标题时（「第N场」「场 N」「【场景 N：…】【第N场…】」等），
  每个场次标题对应剧本里的一个 scene，标题下面的内容都属于这一场；
  标题里标号的分镜（如【分镜 N】）是这一场里的镜头，落进本场的 action 与 dialogue，不要单独成场；
  同一处地点出现在多个场次标题里时各成一场，不要合并成一场 —— 合并了这场戏就没了。
characters 只收录本场真实出场人物，禁止收录旁白/画外音/解说/VO/OS 等非人物说话人；
props 只收录主要道具，并按剧情重要性从高到低排列，每场最多 3 个：
- 保留推动情节、触发关键动作/冲突、承载线索，或能稳定识别角色身份的道具；
- 普通家具、环境陈设、服装、餐具、文具、消耗品及仅在背景中一闪而过的物品不要收录；
- 同一道具不要重复；没有主要道具时返回空数组，不要为了凑数添加物品；
action 必须按原文出现顺序逐条保留本场可视物理动作（角色—动词—对象三元组），
  禁止合并相邻动作、改写动词、或省略中间过渡动作；不要写心理独白，也不要把台词搬进来。
  原文「林薇揉着脖子走进电梯，按下一楼」必须保留为「林薇揉着脖子走进电梯。林薇按下一楼。」（或同等粒度），
  不能只写「林薇进入电梯」。
- action 字数无硬上限，以不丢动作为准；确有把握的同义动作可省略其一，但默认全部保留。
- sound 没有显著声音时给空字符串；有则写关键声音关键词，不要凑 1~2 个。
- dialogue 严格保留剧本原文，不要扩写、解释或补全台词。
- dialogue.speaker 若是旁白/画外音/解说/VO/OS/旁白音 等纯第三人称解说，一律输出「旁白」；旁白只出现在 dialogue.speaker，不得进 characters。
- 台词若是某个角色的画外音或内心独白（用该角色的声音说、画面中人物不开口），speaker 写「角色名（画外音）」，
  不要归成「旁白」；该角色照常进 characters。
- dialogue.emotion 闭集：高兴/愤怒/悲伤/恐惧/惊讶/厌恶/紧张；只标原文或舞台提示已能看出的情绪（如「怒道」「哭着说」），看不出则给空字符串；旁白句 emotion 一律空字符串；非法值不要输出。
$v63_schema_script$,
  1,
  true,
  'drama-text',
  'ScriptDoc Schema：location 只能是单个场地；原文场次标题与 scene 一一对应，【分镜 N】不单独成场'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'schema_semantic_normalization',
  $v63_schema_semantic_normalization$你是单集剧本语义归一化层。输入包含完整 source_script 和机械编号的 source_references。
你必须基于整集上下文判断哪些人物、场景、道具 mention 指向同一故事实体，并判断一个来源场次是否确实包含连续发生的独立地点。
只输出一个 JSON 对象，不要 Markdown，不要解释：
{
  "normalized_script": {
    "title":"","logline":"","style_prefix":"",
    "scenes":[
      {"index":1,"slug":"kebab-英文短名","heading":"场次标题",
       "characters":["规范名"],"props":["规范名"],
       "location":"规范名","time":"日","interior":true,
       "action":"按来源 action 原顺序切出的精确子串",
       "sound":"",
       "dialogue":[{"speaker":"原文说话人","line":"原文台词","emotion":"原文情绪"}]}
    ]
  },
  "entity_groups": [
    {"entity_type":"角色|场景|道具","canonical_mention_id":"来源 mention ID",
     "member_mention_ids":["来源 mention ID"],"confidence":0.0}
  ],
  "scene_lineage": [
    {"normalized_scene_index":1,"source_scene_indexes":[0],
     "action_source_ids":["来源 action ID"],"dialogue_source_ids":["来源 dialogue ID"]}
  ]
}
约束：
- 每个来源 mention ID 必须且只能进入一个 entity_group；canonical_mention_id 必须是该组成员。
- 规范名必须取 canonical_mention_id 对应的原始 value，不得自行生成名称。
- entity_type 必须与 source_references 中该 mention 的 entity_type 逐字一致（角色 / 场景 / 道具）；地点的 entity_type 是「场景」。
- normalized_script.scenes 是归一化场次数组，每场的 index 必须从 1 连续递增，且与 scene_lineage 的 normalized_scene_index 一一对应。
- normalized_script 必须完整；只在叙事确实转换独立地点时拆场，不得为了格式整齐而拆场。
- 拆场时每个归一化场次的 location 必须沿用其来源场次的地点，或使用 entity_groups 中已有的规范地点名；不得发明 source_references 里不存在的地点名。
- 拆场时 action 只能按原字符串顺序切成精确子串，拼接后除空白差异外必须等于来源 action；不得改写、概括或补写。
- 每条来源 dialogue 必须原样且按顺序出现一次，speaker、line、emotion 均不得修改（标点、空白、情绪写法都不能变）。
- normalized_script 中的人物、地点、道具只能使用 entity_groups 选出的规范名。
- 一个实体只能有一个规范名：角色、道具、地点都一样。
  同一件东西在不同场次写成不同名字时（如「雨伞」与「长柄雨伞」、
  「请柬」与「请帖」、「铁剑」与「生锈铁剑」），必须把这些写法归到同一个 entity_group：
  规范名取其中最能识别该实体的一个，其余写法作为 member_mention_ids 并入该组 ——
  资产清单按规范名逐条抽取，留两个规范名就会抽成两张讲同一个东西的设定图（同一个东西出现两次）。
- 但确实不是同一个东西的不要合并：「手机」与「手机壳」、「红伞」与「蓝伞」是两个实体，
  必须各自成组。判断依据是上下文里它们是不是同一件东西，不是名字长得像不像。
- 一个地点只对应一个规范名。同一处地方的不同写法必须归到同一组，规范名取其中最准确的一个；
  不得让同一处地方在归一化后留下两个规范名 —— 场景资产按规范地点抽取，
  留两个规范名就会抽成两张场景设定图（同一个地方出现两次）。
- 若一条地点并列了多个场所（如「皇后的风仪宫，未央宫」），先判断这一场实际发生在哪一处：
  把这条并列 mention 并入那一处的 entity_group（canonical_mention_id 指向那个单地点的 mention），
  并把该场的 location 改成那个规范名；并列的整条不得作为规范名。
- 若并列的是「上位区域 + 其下属场所」（如「紫禁城，风仪宫」），指的是同一处地方，整条保留为规范名。
- 不得为了让场景条目变少而把两个确实不同的地方合并成一个规范名。
- entity_groups 中的每个规范名都必须至少出现在一个归一化场次中；若某个规范名在归一化后不再出现，说明分组有误。
- scene_lineage 必须逐条覆盖 normalized_script.scenes，normalized_scene_index 从 1 连续递增。
- scene_lineage 的 source_scene_indexes 必须包含该场次所引用的全部 action / dialogue 所属的来源场次。
- 即使判断置信度低，也必须给出最终分组并继续；confidence 记录真实置信度。
- 不得根据固定词表、文字外形、标点形式或字符串相似度代替上下文语义判断。
$v63_schema_semantic_normalization$,
  1,
  true,
  'drama-text',
  '语义归一化：一个实体一个规范名（角色/道具/地点）'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'schema_assets',
  $v63_schema_assets$AssetDraftList 结构：
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
- 输入给出的是服务端已经确定的规范资产骨架：条数、顺序、name、type、state_tag、must_split 全部固定。
  逐条照抄骨架，一个不漏、一个不多、顺序完全一致；禁止新增、删除、改名、改类型、改顺序，
  禁止拆出骨架里没有的「本体名·入梦」这类状态条目。
  你只负责写描述：description、identity_invariants、voice_lock 三个字段；
  state_tag 与 must_split 原样照抄，image_prompt 一律输出空字符串。
- 道具只允许从各场 props 数组引用：骨架里的道具已按此筛选好，你只需照抄名字并写描述，
  禁止在描述里引入骨架之外的物品（桌椅、杯子、衣物等普通物品都不要写）。
- 角色 / 场景 / 道具的 image_prompt 一律输出空字符串，由服务端按项目视觉风格生成完整设定图长句。
  禁止自行填写「浅灰背景的角色三视图」或任何短句。
- 每条资产的 description 与 identity_invariants 都必须非空。剧本只给了名字、外貌与场景细节属于创作补全：
  剧本没写明的部分由你按身份、时代、场景合理设计，写成具体、可区分、可编辑的设定；
  禁止留空，禁止写「待确认」「未知」，禁止写「必须保持一致」这类占位句。
- 角色 identity_invariants 是用户可见的外形设定，必须可区分角色，写清：性别、年龄或年龄段、
  发型发色、脸型五官、身材肤色、上装/外套/下装/鞋履（款式与颜色）。
  正例：「20岁，女，杏眼，鼻梁小巧，黑色短发刚过耳，暖黄肤色，白色圆领T恤，深蓝工装背心，深色长裤」。
  剧本没写到的年龄、发型、脸型、服装由你合理补全。
  禁止只写「某某的外貌、发型、服装和年龄必须保持一致」这类空话；禁止三视图、设定图、风格前缀。
  三人不得共用同一套外貌；不要把男性写成女性，不要把中年写成少女。
- 场景不要人物、不要整场剧情动作；每个场景都必须填写 identity_invariants：空间结构、陈设、材质与光影，
  正例：「高二教室，前后黑板，成排课桌椅，侧窗自然光，地面光洁」；禁止外貌/发型/服装/年龄，禁止「必须保持一致」空话。
- 每个道具都必须填写 identity_invariants：外形、材质、磨损与标志性细节，
  正例：「黑色圆珠笔，细杆塑料笔身，金属笔尖，可手持特写」；禁止人物外貌与剧情用途，禁止空话。
- 状态条目（state_tag 不是 base）的 identity_invariants 写明「与本体同一张脸和发型；禁止写成另一个人」，
  状态条目与本体条目各写各的描述，不要把状态变化并进本体 description。
- description 与 identity_invariants 不得互相引用，不得写「同上」；角色 description 可与外形互补，但不得替代 identity_invariants。
- description 与 identity_invariants 只写中文，不要夹英文单词：性别一律写「男」「女」，
  不要写 male/female/man/woman/boy/girl；年龄只写一次，写完不要自己改口，
  严禁出现「35岁 male? no; 35岁，男，……」这类自我更正片段，也不要写成「35岁male?no」「35岁 male」。
- 长度上限：description 不超过 100 字，identity_invariants 不超过 200 字；image_prompt 一律留空。
$v63_schema_assets$,
  1,
  true,
  'drama-text',
  'AssetDraftList Schema：按规范骨架逐条填描述，只写中文，禁止英文性别词与自我更正'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'schema_shots',
  $v63_schema_shots$ShotDraftList 结构：
{
  "chapters": [ { "index": 1, "name": "第一场", "scene_index": 1 } ],
  "shots": [
    {
      "chapter_index": 1,
      "index": 1,
      "title": "局长问责",
      "duration_sec": 12,
      "narrative_goal": "局长进门摔档案并问责。",
      "camera": "中景，跟拍",
      "ambiance": "纸张翻动声，走廊脚步回响，不要配乐。",
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
- scene 只写空间陈设、材质质感与光源氛围，并补一句主色和光质（如「金色光柱」「墨黑轮廓」
  「日光从百叶窗斜切进来，浮尘漂浮」），不要写人物动作，不写人物长相与服装。40～90 字。
- camera 写整条分镜的景别、构图、机位与运镜落点，如「中景，中心构图，微仰，缓慢推近到面部与袖口」。
  16～40 字。禁止只写「缓慢推近」或「中景，静止」。有 shot_beats 时本字段可以留空字符串，
  景别由各镜头的 camera 承担；没有 beats 时它就是这条分镜唯一的运镜说明。
- ambiance 只写画内能听到的声音，如衣袍摩擦、殿内回声、风扇低鸣、纸页轻响、脚步。不要 BGM、配乐、旁白、台词，
  并写明不要配乐。8～40 字。没有明显环境声就留空字符串，不要写占位句。
- 不要写 compiled_prompt 或最终视频提示词，那是下游编译器的活。
- dialogue（台词）优先完整保留：scenes[].dialogue 里的每一句，都要原样落到对应镜头的 dialogue，
  并按镜头顺序用换行拼进该分镜的 dialogue。不要把整段案情堆在最后一个镜头。
  有 emotion 时格式「说话人（情绪）：原文」；无 emotion 时「说话人：原文」；旁白一律「旁白：原文」（不要写情绪括号）。
  角色的画外音/内心独白写成「说话人（画外音）：原文」，括号固定写「画外音」，不再叠情绪括号；
  speaker 为「角色名（画外音）」的照原样保留该标记，禁止改写成「旁白」，发声角色也应列入 character_names。
  禁止摘要、改写、删半句、只留「关键句」或把台词揉进 action / narrative_goal。
  纯动作镜头的 dialogue 留空字符串；有对白的节拍不得因压缩体积而丢掉台词。
- 长度上限（仅压缩非台词字段）：narrative_goal 不超过 40 字，每个镜头 action 40～120 字，
  scene 40～90 字，camera 16～40 字，ambiance 8～40 字，
  continuity_in / continuity_out 与每个镜头 continuity 各不超过 20 字；
  dialogue 不受上述字数上限约束。
$v63_schema_shots$,
  1,
  true,
  'drama-text',
  'ShotDraftList Schema：含 shot_beats / camera / ambiance，场景 40～90 字'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'supervisor_skeleton',
  $v63_supervisor_skeleton$你是 SkeletonSupervisor。请检查以下骨架是否符合约束：
{skeleton_json}

校验项：
1. episodes 数量是否等于 target_episodes（{target_episodes}）
2. 每集 scenes_hint ≤ 6 条
3. characters_recurrent ∈ [1, 8]
4. world_rules ≤ 5 条

输出 JSON：
{"pass": true/false, "additional_issues": [{"field": "...", "severity": "error|warning", "message": "...", "suggestion": "..."}]}$v63_supervisor_skeleton$,
  1,
  true,
  'supervisor',
  'Skeleton 校验 prompt'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'supervisor_script',
  $v63_supervisor_script$你是 ScriptSupervisor。请检查以下 ScriptDoc 是否满足骨架约束：
【骨架】
{skeleton_json}

【ScriptDoc】
{script_json}

校验项：
1. characters_recurrent 中每个角色至少在 1 个 scene 出场
2. 每个 scene.props ≤ 3
3. 每个 scene.characters ≥ 1
4. dialogue.line 严格保留原文（允许换行，不允许改写）
5. scene 数量与 skeleton.scenes_hint 偏差 ≤ 2

输出 JSON：
{"pass": true/false, "additional_issues": [{"field": "...", "severity": "error|warning", "message": "...", "suggestion": "..."}]}$v63_supervisor_script$,
  1,
  true,
  'supervisor',
  'Script 校验 prompt'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'supervisor_assets',
  $v63_supervisor_assets$你是 AssetSupervisor。请检查以下资产清单是否覆盖分场道具：
【ScriptDoc props 汇总】
{props_summary}

【AssetDraftList】
{assets_json}

校验项：
1. 每个 scene 的 props 都有对应资产条目
2. type=角色 且 must_split=false 时不与 state_tag=dream/costume 同名
3. identity_invariants 长度 ≤ 80
4. description 长度 ≤ 100

输出 JSON：
{"pass": true/false, "additional_issues": [{"field": "...", "severity": "error|warning", "message": "...", "suggestion": "..."}]}$v63_supervisor_assets$,
  1,
  true,
  'supervisor',
  'Assets 校验 prompt'
) ON CONFLICT (key, version) DO NOTHING;

INSERT INTO script_prompt(key, content, version, active, attribution, description)
VALUES (
  'supervisor_shots',
  $v63_supervisor_shots$你是 ShotSupervisor。请检查以下分镜是否合规：
【ScriptDoc dialogue 汇总】
{dialogue_summary}

【ShotDraftList】
{shots_json}

校验项：
1. shots[].duration_sec ∈ [5, 15]
2. shots[].dialogue 能在 script.dialogue 中找到对应原文
3. cast_count == character_names.size
4. 同一 chapter_index 的 shot 数不超过合理上界（防爆拆）

输出 JSON：
{"pass": true/false, "additional_issues": [{"field": "...", "severity": "error|warning", "message": "...", "suggestion": "..."}]}$v63_supervisor_shots$,
  1,
  true,
  'supervisor',
  'Shots 校验 prompt'
) ON CONFLICT (key, version) DO NOTHING;
