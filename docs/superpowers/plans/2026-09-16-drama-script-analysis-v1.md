# 多模型剧本解析与最少分镜 V1 实施计划

> 依据：`docs/superpowers/specs/2026-09-16-drama-script-analysis-design.md`

**目标：** 在不改变现有分镜页面主要交互的前提下，提高 OpenAI-compatible 文本模型输出稳定性，取消静默规则兜底，并把同场景、可在目标时长内连续表达的相邻分镜合并。

**实现边界：** V1 不新增 Beat 编辑 UI 和分析运行表。先使用现有 `ScriptDoc` 与 `ShotDraftList` 建立可观察效果，再为后续 ScriptIR 持久化保留类边界。

## 任务 1：建立文本模型能力配置

**修改文件：**

- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/ResolvedTextModel.java`
- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/TextChatGatewayImpl.java`
- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/OpenAiCompatChatClient.java`
- `dit-server/src/test/java/cn/aigc/dit/server/drama/text/TextChatGatewayImplTest.java`
- `dit-server/src/test/java/cn/aigc/dit/server/drama/text/OpenAiCompatChatClientTest.java`

步骤：

1. 先写失败测试，覆盖非 DeepSeek JSON 请求也发送 token 上限。
2. 写失败测试，覆盖 `plain_json`、`supportsSystemRole=false`、`tokenParameter=max_completion_tokens` 和可配置思考开关。
3. 将 `ai_model.adapter_config` 传入 `ResolvedTextModel` 和 HTTP 客户端。
4. 实现保守默认值：JSON 请求默认 `json_object`；DeepSeek 默认关闭思考；其他行为只由配置开启。
5. 运行：`mvn -q -pl dit-server -am -Dtest=OpenAiCompatChatClientTest,TextChatGatewayImplTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## 任务 2：把原样重试改为带诊断修复

**修改文件：**

- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextPrompts.java`
- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextServiceImpl.java`
- `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextServiceImplTest.java`

步骤：

1. 先写失败测试，断言第二次请求包含首轮错误原因、输出合同和待修复内容。
2. 新增受长度限制的 JSON 修复提示，避免把用户原文写入日志。
3. 保留两次总尝试上限；网络异常不盲目重试，合同异常才进入修复。
4. 运行：`mvn -q -pl dit-server -am -Dtest=DramaTextServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## 任务 3：取消静默规则解析与分镜兜底

**修改文件：**

- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextService.java`
- `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextServiceImpl.java`
- `dit-server/src/main/java/cn/aigc/dit/server/drama/DramaContract.java`
- `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextServiceImplTest.java`
- `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextDraftPathsTest.java`

步骤：

1. 将原有“失败后返回规则结果”的测试改为“抛出可读业务异常”。
2. 未绑定文本模型时明确提示先绑定模型。
3. 模型输出合同连续失败时返回阶段化错误，不调用 `ScriptParser`、`DramaAssetDrafts` 或 `DramaShotDrafts`。
4. `engine` 对成功结果只返回 `llm`；旧常量暂保留用于老数据兼容，但不进入新解析路径。
5. 运行相关单元测试。

## 任务 4：实现同场连续分镜最小化

**新增/修改文件：**

- 新增 `dit-server/src/main/java/cn/aigc/dit/server/drama/text/ShotDraftMinimizer.java`
- 新增 `dit-server/src/test/java/cn/aigc/dit/server/drama/text/ShotDraftMinimizerTest.java`
- 修改 `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextServiceImpl.java`
- 修改 `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextPrompts.java`
- 修改 `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextPromptsTest.java`

步骤：

1. 先写失败测试：同 chapter、同 location、总时长不超过上限的相邻草稿合并为一镜。
2. 写边界测试：跨 chapter、不同 location、状态切换或总时长超限时不得合并。
3. 合并时按顺序拼接动作与台词，去重合并角色/道具，保留首镜 `continuity_in` 和末镜 `continuity_out`。
4. 删除 Prompt 中“一场至少 2 镜”“不要压成少数超长镜”等冲突要求，改为 15 秒内优先合并。
5. `draftShots` 在规范化与时长钳位后调用最小化器。
6. 运行最小化器和 Prompt 测试。

## 任务 5：回归、文档与效果样例

**修改文件：**

- 必要的现有测试文件
- `docs/superpowers/specs/2026-09-16-drama-script-analysis-design.md`

步骤：

1. 删除或改写强制第一场四至八镜、每场至少两镜的断言。
2. 运行：`mvn -q -pl dit-server -am test`。
3. 运行：`mvn -q test`；若全仓测试成本过高，至少执行所有受影响模块并记录限制。
4. 使用固定 `ShotDraftList` 样例对比最小化前后数量、顺序、台词和资产引用。
5. 执行 `git diff --check` 和工作区审查，确认未覆盖无关改动。

## V1 完成标准

- 所有 JSON 请求都按模型配置正确发送输出 token 参数。
- 合同错误的第二次调用携带具体修复反馈，不再原样重试。
- 未绑定、传输失败或两次合同失败均明确报错，不生成规则结果。
- 无硬边界且可在目标时长内连续表达的相邻分镜被合并。
- 现有图片、音色、台词、字幕、视频生成和成片接口不变。
- 受影响测试全部通过。
