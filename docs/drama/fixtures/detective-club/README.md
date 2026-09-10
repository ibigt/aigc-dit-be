# 侦探社黄金样例

`DetectiveClubRegressionTest` 的输入与期望输出。

## 唯一事实源

**权威副本在 `dit-server/src/test/resources/fixtures/detective-club/`**，单测按 classpath 读取。
本目录只是给人看的交接副本 —— `docs/` 不在 test classpath 上，两边各留一份是刻意的，
但改动只允许从权威副本单向复制过来：

```sh
cp dit-server/src/test/resources/fixtures/detective-club/{source.txt,expected-script.json,expected-assets.json,expected-shots.json} \
   docs/drama/fixtures/detective-club/
```

## 文件

| 文件 | 作用 |
|---|---|
| `source.txt` | 剧本原文输入 |
| `expected-script.json` | 期望分场剧本，逐字段钉死（ScriptDoc） |
| `expected-assets.json` | 资产清单「必含 / 必不含 / 总条数」断言 |
| `expected-shots.json` | 章数与第一场节拍，其余边界取 `DramaContract` 常量 |

## 为什么资产和分镜不逐字钉死

`description` / `image_prompt` 由主力的 `DramaAssetDrafts` 模板生成，`DramaShotDrafts` 的镜数由
动作行与对白行推导。逐字钉死会让「主力改模板」变成乙的回归红，所以这两份只钉**合同要求**：
名字、类型、`state_tag`、`must_split`、`needs_human`、时长白名单、`cast_count` 一致性、第一场节拍覆盖。

## 现状：`source.txt` 是合成样例

《侦探社_测试剧本.txt》原件在 `E:\project\2026\9.3_AIGC\`，本仓库与本机均无副本，
当前 `source.txt` 依据《乙工作内容》描述（五场、洛白/夏安/局长/徐医生、古铜怀表、入梦独立、
幻梦侦探社/市中心医院ICU/静心心理诊所）手写。

**拿到真本后的动作**：替换 `source.txt` → 按真本重写三份 expected → 跑
`mvn -o -pl dit-server -am test -Dtest=DetectiveClubRegressionTest`。解析规则本身无需改动。

## 已知的格式假设

规则分场认三种行：`第N场`（或 `场N`）开头的场标记行、`名字：台词` 对白行、其余为动作行；
另支持 `人物：` / `道具：` / `地点：` / `时间：` / `声音：` 元信息行补足规则无法识别的字段。
真本若使用 `1-1`、`【场景】` 之类写法，需要扩 `ScriptParser.SCENE_START`；
若不使用元信息行，`props` 与 `sound` 会为空 —— 这两点已在 `docs/drama/regression.md` 待确认清单里。
