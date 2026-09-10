# 剧本理解层回归

覆盖《乙工作内容》四件交付：分场剧本、资产清单、分镜草稿、跨集沿用。

## 怎么跑

```sh
mvn -o -pl dit-server -am test          # 全量
mvn -o -pl dit-server -am test -Dtest=DetectiveClubRegressionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 输入

`dit-server/src/test/resources/fixtures/detective-club/source.txt`（`docs/drama/fixtures/` 有交接副本，
权威在 test/resources）。

**当前是真本的替身**：《侦探社_测试剧本.txt》在 `E:\project\2026\9.3_AIGC\`，本机与仓库均无副本，
现样例按《乙工作内容》描述手写（五场、洛白/夏安/局长/徐医生、古铜怀表、入梦独立、
幻梦侦探社/市中心医院ICU/静心心理诊所）。拿到真本后替换 `source.txt` 并重写三份 expected，
解析规则本身不需要改。

## 断言与红绿记录

| 用例 | 钉住的东西 | 状态 |
|---|---|---|
| `ScriptParserTest` ×9 | 五场切分、对白/动作归位、heading 拆 location/time/interior、原文零丢失、无标记时不丢梗概 | 绿 |
| `DetectiveClubRegressionTest#scriptMatchesGoldenSample` | 逐字段对齐 expected-script.json | 绿 |
| `…#firstSceneCastMatchesContract` | 第一场含洛白/夏安/局长，局长有对白 | 绿 |
| `…#assetsMatchGoldenSample` | 18 条资产必含、total 不得虚增、四字段非空、needs_human | 绿 |
| `…#dreamStateIsNeverMergedIntoBase` | 洛白只有一条 base；入梦/梦魇独立 2 条 | 绿 |
| `…#shotsMatchGoldenSample` | 5 章、时长白名单、cast_count 一致、名只引用已有资产、第一场四节拍 | 绿 |
| `DramaTextServiceImplTest` ×13 | 引擎判定、JSON mode、围栏容忍、index 修复、回落不误报 llm、换绑提示不被吞 | 绿 |
| `DramaTextDraftPathsTest` ×8 | 资产/分镜两条模型路径的合同修复与回落 | 绿 |
| `DramaTextConcurrencyTest` | 8 线程 ×20 轮交替两系列，engine 与 script 同源 | 绿 |
| `EpisodeNameGuardTest` ×6 | 跨集沿用原名、改名收回、新状态资产保留、新角色放行 | 绿 |

## 三类已知失败，已固化成断言

1. **漏古铜怀表** —— `assetsMatchGoldenSample` 必含「古铜怀表」，且 `total_expected` 防虚增。
2. **合并梦境态** —— `dreamStateIsNeverMergedIntoBase`：本体 `state_tag` 必须 base、description 里
   不得出现状态名；dream 条目恰好 2 条（洛白·入梦、梦魇）。
3. **人数对不上** —— `cast_count == character_names.length`，且人名不在资产名里会被直接丢弃。

## engine 三列口径

| `engine` | `script.scenes` | `textServiceAvailable` | 含义 |
|---|---|---|---|
| `pending` | 空 | false | 历史行：理解层合入前导入的，只存了 `source_text` |
| `rules` | 有 | true | 该系列未绑定文本模型，规则分场 |
| `llm` | 有 | true | 该系列绑定了文本模型且输出符合合同 |
| `rules` | 有 | true | 绑了模型但调用失败或输出不合合同，**静默回落**（看日志 `[drama-text]`） |

最后一行不另设第四值：加值要改 `V1__init_schema.sql` 的 CHECK 约束并补 migration，
且 `ScriptDocsTest:38` 钉死了 `normalizeEngine` 的宽松兜底。回落与真规则在 UI 上同等可信，
区别只在日志。判定模型是否可用请以 `resolve(seriesId)` 为准。

**历史 `pending` 行不做回填 migration**：`script IS NULL` 天然区分「从未解析过」，UI 显示「未知」即可。

## 待主力确认

1. **剧本格式假设**：规则只认 `第N场` / `场N` 场标记行、`名字：台词` 对白行，其余为动作行；
   `人物：` / `道具：` / `地点：` / `时间：` / `声音：` 元信息行补足规则识别不了的字段。
   真本若写 `1-1`、`【场景】`，需要扩 `ScriptParser.SCENE_START`；若没有元信息行，`props` 与 `sound` 会空。
2. **TEXT 模型没有 bootstrap**：`bootstrap/` 下只有 MiniMax / Mock / RunningHub，都要人工在
   后台建一条 `category=text` 的模型，否则 `listEnabledTextModels()` 为空、项目永远只能「不绑」。
3. **`llmAvailable` 吞掉「绑定已停用」**：`TextChatGatewayImpl:28-34` catch 成 false，
   `DramaService:187-189` 因此提示「未绑定可用文本模型」，而用户其实绑了只是被停用。
   理解层内部改用 `resolve()`，但入口提示仍会误导。
4. **`parseScript` 在事务里同步调模型**：`importSource` 与 `generateScript` 共用一个方法且无 Mode 参数，
   `CHAT_TIMEOUT=60s`。已按「传输异常不重试、仅合同不合时重试一次」把最坏压到一次往返 + 一次重试，
   异步化或加 Mode 参数仍需主力定。
5. **`inferStateTag` 不区分类型**：场景名带「梦境」也会被标成 dream + must_split（样例里的
   「梦境走廊」曾因此被标错，现改名「潜意识回廊」规避）。是否只该对角色生效，请主力定。
6. **模型自写的 `image_prompt` 会绕过风格前缀**：`DramaAssetDrafts.ensurePrompts` 只补空值与旧版一行式，
   不会重写模型给的长 prompt。当前只有「二次元漫画风」一种风格，影响有限。
7. **跨集落点**：`drama_episode.inherited_from_episode_id` 有列无服务。
   理解层已备好 `DramaTextService.parseNextEpisode(seriesId, raw, lockedAssetNames)`，等主力接线。
