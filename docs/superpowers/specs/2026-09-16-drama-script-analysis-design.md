# 多模型剧本解析与最少分镜设计

日期：2026-09-16  
状态：方案已确认，V1 核心改造已实现；ScriptIR 与完整能力编排待后续迭代

## 1. 背景

当前短剧链路存在两个直接影响客户体验的问题：

1. DeepSeek 之外的文本模型容易输出截断、非 JSON 或合同不完整的结果；当前重试会原样重复请求，并在失败后静默切换到规则解析。
2. 分镜规则强制每场至少两镜、第一场四至八镜，且提示词要求避免少量长镜，直接违背“15 秒能表达完就不要拆”的产品目标。

现有规则兜底还包含特定演示剧本的人物和道具判断，不能泛化到客户剧本。新设计不再使用规则生成剧情语义或分镜。

## 2. 已确认的产品约束

- 一张现有分镜卡片继续对应一次视频生成任务和一个最终成片片段。
- 优先用一个最长 15 秒的视频表达同场景内自然连续的内容。
- 只有以下情况强制拆分：
  - 地点或时间明确跳转；
  - 人物、服装、道具等状态不能连续表达；
  - 完整表达预计超过所选模型支持的最大时长；
  - 所选视频模型的参考素材、人物数、音频或叙事能力限制无法承载。
- 景别变化、运镜、转身、连续动作和短台词本身不构成强制拆分条件。
- 取消规则分镜兜底。模型与修复流程均失败时明确返回失败诊断。
- 前端最小改动：不新增 Beat 时间轴、展开面板或合并工作台；保留现有台词、资产、参考图、提示词、生成和拼接交互。
- Beat 是后端内部的理解、覆盖检查和编排结构，不假设视频模型原生支持 Beat JSON 或精确时间码。

## 3. 目标与非目标

### 3.1 目标

- 通过能力配置适配多种 OpenAI-compatible 文本模型。
- 将模型输出的格式稳定性与剧本理解质量分开治理。
- 每个原文对白和关键动作均可追溯、可校验。
- 在硬边界和视频模型能力范围内生成尽可能少的分镜。
- 保持现有图片、角色音色、台词、字幕、出片和成片链路兼容。
- 解析失败可解释、可重试，禁止静默伪成功。

### 3.2 非目标

- 不引入 Toonflow 的完整多 Agent、无限画布或生产工作台。
- 不承诺任意文本模型达到相同语义质量。
- 不让普通用户编辑内部 Beat 时间轴。
- 不在首版实现跨地点或跨时间的一镜到底。
- 不依赖视频模型严格执行秒级时间码。

## 4. 总体架构

```text
原始剧本
  -> 确定性预处理（保留原文并分配段落/对白 ID）
  -> 剧本理解模型（生成 ScriptIR）
  -> ScriptIR 结构与覆盖校验
  -> 最少分镜编排器（按硬边界和模型能力分组）
  -> 视听表达模型（为分镜补充可执行的画面表达）
  -> 分镜语义校验
  -> 定点修复
  -> 现有 DramaShot / 前端分镜卡片
```

建议将当前 `DramaTextServiceImpl` 的职责拆为：

- `DramaAnalysisOrchestrator`：事务外编排、阶段状态和诊断汇总；
- `ScriptPreprocessor`：只做清洗、编号和原文锚点；
- `ScriptUnderstandingService`：调用文本模型生成 ScriptIR；
- `ModelCapabilityAdapter`：按模型能力构造请求并读取结果；
- `ClipPlanningService`：在约束下生成最少的 `DramaShot` 草稿；
- `StoryboardEnrichmentService`：生成镜头动作、构图和连续性描述；
- `DramaQualityValidator`：执行结构、覆盖、顺序、时长和连续性检查；
- `DramaRepairService`：携带明确错误列表进行局部修复。

首版允许按上述职责建立类边界，但可继续由现有服务对外暴露接口，以减少控制器与前端改动。

## 5. ScriptIR 数据契约

ScriptIR 是模型理解结果，不直接等同于最终分镜：

```json
{
  "schemaVersion": "1.0",
  "scenes": [
    {
      "sceneId": "S001",
      "heading": "内景·侦探社·夜",
      "locationName": "侦探社",
      "timeOfDay": "夜",
      "sourceParagraphIds": ["P001", "P002", "P003"],
      "beats": [
        {
          "beatId": "B001",
          "type": "action",
          "summary": "洛白走到桌前并拿起照片",
          "sourceParagraphIds": ["P002"],
          "characterNames": ["洛白"],
          "propNames": ["照片"],
          "estimatedDurationMs": 4500,
          "hardBoundaryBefore": false,
          "stateIn": {},
          "stateOut": {"洛白.hand": "照片"}
        },
        {
          "beatId": "B002",
          "type": "dialogue",
          "speakerName": "洛白",
          "dialogue": "终于找到你了。",
          "sourceDialogueId": "D001",
          "sourceParagraphIds": ["P003"],
          "estimatedDurationMs": 3000,
          "hardBoundaryBefore": false
        }
      ]
    }
  ]
}
```

约束：

- `sourceParagraphIds` 和 `sourceDialogueId` 必须引用预处理阶段生成的 ID。
- 模型不能改写原始台词；规范化仅允许处理空白与标点差异。
- `estimatedDurationMs` 是编排依据，不是向视频模型承诺的精确时间码。
- 状态字段只记录原文明确或可直接推导的可见状态，不生成心理活动。
- 第一版 ScriptIR 可以只保存在分析快照 JSONB 中，不必拆成多张关系表。

## 6. 多文本模型适配

复用 `ai_model.adapter_config` 存储文本模型能力。V1 已支持的配置是：

```json
{
  "structuredOutput": "json_object | plain_json",
  "tokenParameter": "max_tokens | max_completion_tokens",
  "supportsSystemRole": true,
  "enableThinking": false,
  "temperature": 0.1
}
```

JSON Schema、tool arguments、自定义内容路径与嵌套 reasoning control 是后续目标合同，尚未在 V1 实现：

```json
{
  "textProtocol": "openai_chat",
  "structuredOutput": "json_schema",
  "tokenParameter": "max_tokens",
  "supportsSystemRole": true,
  "reasoningControl": {
    "field": "chat_template_kwargs.enable_thinking",
    "value": false
  },
  "contentPath": "choices[0].message.content",
  "temperature": 0.1,
  "maxOutputTokens": 8192
}
```

结构化输出按能力依次选择，而不是对所有模型强制 `json_object`：

```text
JSON Schema
  -> Tool / Function Arguments
  -> JSON Object
  -> 带边界标记的普通 JSON
```

受支持模型分级：

- 一级：DeepSeek、通义千问、豆包等主力模型，必须通过完整质量测试集；
- 二级：支持上述结构化协议的 OpenAI-compatible 模型，按能力配置适配；
- 三级：只能输出普通文本的模型，允许受限使用并显示能力警告。

所有 JSON 模型请求均会收到实际的输出 token 上限。V1 允许能力配置覆盖思考开关，
并暂保留 DeepSeek 模型名的兼容默认值，避免旧配置突然产生长 reasoning。

## 7. 最少分镜编排

编排器不理解或创造剧情，只消费已经通过校验的 Beat。

### 7.1 时长估计

- 对白时长：按语言、字符数、标点停顿和可配置语速估算；
- 动作时长：优先采用理解模型给出的估计，并在合理上下限内校准；
- 同时发生的动作与对白允许重叠，不简单相加；
- 为镜头建立和动作缓冲保留安全余量。

### 7.2 分组算法

对每个 Scene 按顺序执行受约束的顺序分段：

1. 遇到硬边界必须开启新分镜；
2. 候选 Beat 加入后若超过所选视频模型最大时长，则在前一 Beat 结束；
3. 若合并后超过参考图、参考音频、人物数或动作复杂度上限，则拆分；
4. 在所有合法分段中优先选择分镜数最少的结果；
5. 分镜数相同时，优先避免过满、台词被截断和状态切换密集的方案。

不再设置“每场至少两镜”或“第一场四至八镜”。允许一个 Scene 只产生一个 15 秒分镜。

### 7.3 视频模型能力

在 `ai_model.adapter_config` 中增加视频叙事能力配置：

```json
{
  "temporalPromptMode": "continuous_action",
  "supportsInternalCuts": false,
  "maxActionPhases": 2,
  "maxCharactersPerClip": 2,
  "maxVisualReferences": 3,
  "maxAudioReferences": 1
}
```

其中 `maxActionPhases` 等字段必须来自固定测试集的实测结果或明确的提供方限制。没有测试结论时采用保守默认值，不按模型名称猜测。

## 8. 视听表达与提示词编译

内部 Beat 不直接原样传给视频模型。`StoryboardEnrichmentService` 先把同一分镜内的 Beat 压缩为一个主要事件和自然连续动作，再由现有 `DramaStoryboardCompiler` 编译。

按视频模型能力生成不同表达：

- `sequence`：允许使用先后顺序清晰的阶段描述；
- `continuous_action`：只表达自然连续动作，不要求内部切镜或精确时间码；
- `weak`：一个分镜只保留一个主要事件。

首版不承诺精确的 `0-4s` 时间控制。Beat 时间只用于内部估算、字幕和质量检查。

## 9. 兼容现有资产、音色与台词

### 9.1 图片与资产

- 保留 `drama_asset`、资产锁定和 `reference_asset_ids`；
- 分镜引用取内部 Beat 涉及资产的去重并集；
- 继续使用现有 Picture 序号和参考生视频链路；
- 超过视频模型参考图上限时作为技术硬边界拆分。

### 9.2 音色

- 保留 `voice_lock`、`voice_reference_key` 和 `voice_reference_mime`；
- 通过内部对白的说话人精确收集角色音色；
- 超过模型音频参考数量上限时禁止静默丢弃，改为拆分或明确报错。

### 9.3 台词与字幕

- `DramaShot.dialogue` 继续作为前端、现有编译器和字幕服务使用的兼容字段；
- 该字段由分镜包含的 ScriptIR 对白按顺序生成；
- 用户在现有文本框修改台词后，将该分镜标记为人工修改，后续校验以人工版本为准；
- 第一版不要求前端直接编辑 Beat，避免双向同步复杂度。

### 9.4 老数据

- 老分镜无需补建 Beat 即可继续查看、编辑、生成和拼接；
- 已生成视频、参考资产和手动 Prompt 不自动失效；
- 只有用户主动重新解析/重新拆镜时才进入新流程。

## 10. 校验、修复与失败语义

### 10.1 结构校验

- JSON 可解析且符合当前 schemaVersion；
- ID 唯一且引用有效；
- Scene 和 Beat 顺序合法；
- 必填字段与枚举合法。

### 10.2 语义校验

- 原文对白 ID 覆盖且恰好一次、顺序一致；
- 关键动作段落得到覆盖；
- 不出现原文不存在的人物、道具、地点或剧情事实；
- 每个分镜符合视频模型时长和参考能力；
- 相邻分镜如果同场景、无硬边界且合并后仍合法，标记为“可避免拆分”；
- `continuity_out` 与下一分镜的 `continuity_in` 不冲突。

### 10.3 定点修复

第一次输出不合格时，不原样重试。修复请求携带：

- 原始合同或 JSON Schema；
- 上次输出；
- 结构化错误列表；
- 只允许修改的 Scene / Beat / 字段范围。

优先执行纯 JSON 修复；涉及事实遗漏时仅重跑问题 Scene。默认限制总模型调用次数，避免无限循环。

### 10.4 最终失败

- 不调用 `ScriptParser` 或 `DramaShotDrafts` 伪造成功结果；
- API 返回阶段、错误码、可读原因、模型和重试建议；
- 保留原始剧本及上一次合法阶段产物；
- 前端沿用现有错误横幅展示，不新增复杂页面。

## 11. API 与数据库改造

首版建议新增分析运行表，避免使用 ThreadLocal 表示实际引擎和诊断：

```text
drama_analysis_run
- id
- series_id
- episode_id
- status
- stage
- text_model_id
- schema_version
- source_fingerprint
- script_ir_json
- diagnostics_json
- attempt_count
- started_at
- finished_at
```

`drama_shot` 首版可以不增加可编辑 Beat 字段。为可追溯性增加：

```text
- source_beat_ids JSONB NOT NULL DEFAULT []
- analysis_run_id BIGINT NULL
```

现有 `DramaScriptAnalyzeResp` 保留 `actualSceneCount`、`actualShotCount`、`engine` 和 `warnings`，并增加可选的：

```json
{
  "analysisRunId": 123,
  "stage": "completed",
  "quality": {
    "dialogueCoverage": 1.0,
    "actionCoverage": 1.0,
    "avoidableSplitCount": 0
  }
}
```

旧客户端可以忽略新增字段。

## 12. 用户交互

前端不新增 Beat 交互。用户路径保持：

```text
导入剧本 -> 解析 -> 确认资产 -> 拆分镜 -> 编辑/确认 -> 出片 -> 合成
```

用户可见变化限定为：

- 分镜数量减少；
- 单镜动作描述更连贯；
- 单镜时长优先接近但不强制等于 15 秒；
- 解析失败明确显示原因；
- 分镜计数和现有编辑方式保持不变。

## 13. Toonflow 的借鉴边界

借鉴：

- 规划、执行、监督职责分离；
- 在分镜前建立中间事件结构；
- 独立质量审查；
- 生成组受 15 秒上限约束。

不照搬：

- 多 Agent 和无限画布架构；
- 长对白按固定字数强制拆镜；
- 约八秒的单镜限制；
- 与本项目“15 秒优先合并”冲突的固定分镜数量规则。

## 14. 测试与验收

### 14.1 文本模型合同测试

同一组剧本至少覆盖一级模型，并使用录制响应或受控替身测试：

- 合法 JSON Schema 输出；
- Markdown 包裹 JSON；
- 截断 JSON；
- tool arguments 输出；
- 空 content / reasoning-only；
- 未知字段与字段别名；
- 修复成功和修复失败。

### 14.2 剧本质量集

覆盖：

- 单场连续动作；
- 多场景和时间跳转；
- 双人及多人对白；
- 旁白、插叙、梦境；
- 服装、道具和人物状态变化；
- 无规范场景标题的自然语言剧本；
- 长剧本与接近 token 上限的输入。

核心断言：

- 对白覆盖率 100%；
- 不产生规则兜底结果；
- 无硬边界时，相邻分镜不存在可合法合并且总时长不超过上限的情况；
- 场景、对白和动作顺序不改变；
- 每个分镜满足所选视频模型能力约束。

### 14.3 视频模型能力测试

对每个视频适配器建立固定样例：

- 单动作；
- 两个连续动作；
- 动作加短台词；
- 两人轮流说话；
- 同场景景别变化；
- 明确场内切镜。

能力档案以实测结果更新，不把营销描述直接当成可执行能力。

## 15. 发布策略

1. 先在配置中增加新解析链路开关，默认仅测试系列启用；
2. 影子运行新旧 LLM 结果，只记录指标，不覆盖用户数据；
3. 对内部样例和客户代表剧本做人工盲评；
4. 新链路小流量启用，禁止规则回退；
5. 观察解析成功率、平均模型调用次数、对白覆盖率、平均分镜数和人工重拆率；
6. 达标后成为默认链路，再删除旧规则分镜入口和冲突测试。

建议验收指标：

- 一级文本模型结构成功率不低于 98%；
- 对白覆盖率 100%；
- 可避免拆分数量为 0；
- 相同测试集的平均分镜数相较当前版本显著下降；
- 解析失败 100% 显式呈现，不出现静默规则回退。

## 16. 首版开发范围

为尽快看到效果，首版聚焦：

1. 移除第一场四至八镜、每场至少两镜和相反提示词；
2. 文本模型请求能力配置、统一 token 上限和带错误反馈的修复重试；
3. 禁止 `parseScript`、`extractAssets`、`draftShots` 静默规则回退；
4. 在现有 `ScriptDoc` / `ShotDraftList` 上实现保守的相邻分镜合并；
5. 实现同场景、连续性标记完整且一致、十五秒以内的最少分镜效果；
6. 保持当前前端交互与 `DramaShot` 生成链路；
7. 更新和新增测试，移除强制多镜断言。

尚未实现的范围：带 source ID 的 ScriptIR、对白/动作覆盖率确定性校验、视频模型能力策略、
后台拆镜失败的持久化可见诊断、分析运行持久化、完整模型实测矩阵和高级局部重跑。
这些属于后续迭代，不在 V1 完成声明中。
