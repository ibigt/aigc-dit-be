# MiniMax-H3 SGLang 接入

管理端新建视频模型，选择 `MiniMax-H3（SGLang）`：

| 字段 | 值 |
| --- | --- |
| adapterKey | `minimax-h3-sglang` |
| provider | `SGLang`（由协议目录填写） |
| modelKey | `MiniMaxAI/MiniMax-H3`，或部署服务实际使用的模型标识 |
| endpoint | `http://模型服务器IP:30010`，也支持以 `/v1` 或 `/v1/videos` 结尾 |
| category | `video` |
| apiKey | 未开启鉴权时留空；开启后填写 Bearer Token |

后端使用 `POST /v1/videos` 提交，`GET /v1/videos/{id}` 查询，状态为 `completed` 后通过 `GET /v1/videos/{id}/content` 下载完整 MP4 并写入对象存储。下载也携带配置的鉴权头。后端重启后可恢复已有任务轮询。

支持模式：

- `t2v` → `t2va`：文生视频及音频。
- `i2v` → `fl2va`：首帧、尾帧或首尾帧，条件角色为 `keyframe`，帧索引为 `0` / `-1`。
- `r2v` → `ref2va`：图片、视频、音频参考，条件角色为 `reference`。音频最多三段，允许仅音频参考。

原生模型的 `--model-variant fl2va` 部署提供文生视频和首尾帧；`--model-variant ref2va` 提供参考生成。通常需配置两条模型记录及不同服务地址，并分别选择对应能力。详见 [SGLang 官方说明](https://github.com/sgl-project/sglang/blob/main/docs/cookbook/diffusion/MiniMax/MiniMax-H3.mdx)。

时长支持 5–15 秒，默认 5 秒（服务端约束 `target.duration_seconds ∈ [5, 15]`，4 秒会被 HTTP 400 拒绝）。每次任务只支持一个视频，多份输出请分别提交。取消任务仅停止后端跟踪，不会终止远端 GPU 推理。

`adapterConfig` 可配置：

```json
{
  "shortEdge": 768,
  "numInferenceSteps": 50,
  "flowShift": 12.0,
  "audioFlowShift": 3.0,
  "seed": 1101
}
```

不配置 `shortEdge` 时，使用请求分辨率的短边像素值（如 `768P`），未指定时为 768。`shortEdge` 可固定部署验证过的尺寸。未配置 `seed` 时使用服务默认随机行为。

参考素材会转换为有效期 24 小时的对象存储预签名下载地址，SGLang 服务器必须能访问这些地址。后端在 Docker 中运行时，服务地址不能填写容器自身的 `127.0.0.1`；应使用可达的宿主机或模型服务器地址。

测试连接只读调用 `GET /v1/models`，不会提交生成任务。

本地慢速推理的 HTTP 等待可在后端配置（以下为默认值）：

```yaml
dit:
  generate:
    sglang:
      request-timeout-seconds: 3600
      poll-timeout-seconds: 60
      download-timeout-seconds: 600
```

提交等待上限为 1 小时，查询单次等待为 60 秒；查询遇到临时 HTTP 5xx 或网络超时会继续轮询。服务端主动返回 HTTP 500 不会因增大客户端超时而消失。提交失败且未收到任务 ID 时，不自动重复提交，因为远端可能已受理并开始生成。需核查 SGLang 服务端异常堆栈及任务状态。

本地慢速推理的 HTTP 等待可在后端配置（以下为默认值）：

```yaml
dit:
  generate:
    sglang:
      request-timeout-seconds: 3600
      poll-timeout-seconds: 60
      download-timeout-seconds: 600
```

提交等待上限为 1 小时，查询单次等待为 60 秒；查询遇到临时 HTTP 5xx 或网络超时会继续轮询。服务端主动返回 HTTP 500 不会因增大客户端超时而消失。提交失败且未收到任务 ID 时，不自动重复提交，因为远端可能已受理并开始生成。需核查 SGLang 服务端异常堆栈及任务状态。
