# 短剧字幕功能部署

适用范围：当前 `master` 的后端、前端，以及沿用 `/opt/aigc-dit` 和 `/mnt/ai-gc` 目录结构的 Linux 服务器。下面以 `172.171.113.250`、SSH 用户 `fnii` 为例。执行部署前先确认服务器上正在使用的数据库和对象存储配置；更新现有服务时只重建 `backend`、`frontend`，不要执行 `docker compose down -v`。

## 功能与依赖

分镜视频生成后，后端把任务写入 `drama_shot_caption`，异步工作器提取音频，使用 Silero VAD 检测语音，再用中文 Forced Alignment 将已有台词对齐到音频。对齐不可用时尝试 VAD 时间分配。最后 FFmpeg/libass 生成带字幕的分镜视频，并保存字幕 JSON；成片字幕另有 WebVTT 轨道。前端在字幕任务 `READY` 后播放带字幕的分镜视频。

这套处理在 Java 后端容器内运行，不需要单独部署 Python、ASR 服务或 GPU。完整后端镜像需要 JRE 17、FFmpeg（含 `ass` 滤镜）和中文字体；仓库的完整 `Dockerfile` 已安装这些依赖。`deploy/Dockerfile.prebuilt` 只替换已有镜像里的 JAR，因此目标服务器必须先有可用的 `aigc-dit-backend:local` 基础镜像。

## 1. 检查模型文件

服务器应有以下文件，路径必须与当前 `docker-compose.yml` 中的容器内路径一致：

```text
/mnt/ai-gc/models/silero_vad.onnx
/mnt/ai-gc/models/forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx
/mnt/ai-gc/models/forced-alignment/assets/g2pm_cedict.json
/mnt/ai-gc/models/forced-alignment/assets/g2pm_char2idx.json
/mnt/ai-gc/models/forced-alignment/assets/g2pm_idx2class.json
/mnt/ai-gc/models/forced-alignment/assets/g2pm_lstm.bin
/mnt/ai-gc/models/forced-alignment/assets/g2pm_lstm.json
/mnt/ai-gc/models/forced-alignment/assets/vocab_zh.json
```

登录目标服务器后检查：

```bash
for file in \
  /mnt/ai-gc/models/silero_vad.onnx \
  /mnt/ai-gc/models/forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx \
  /mnt/ai-gc/models/forced-alignment/assets/{g2pm_cedict.json,g2pm_char2idx.json,g2pm_idx2class.json,g2pm_lstm.bin,g2pm_lstm.json,vocab_zh.json}; do
  test -s "$file" || printf '缺失或为空：%s\n' "$file"
done
```

目前 `172.171.113.250` 已放置上述模型。模型保存在后端仓库的 `models/`，大文件由 Git LFS 管理；它们不会包含在 JAR 或前端构建产物中。部署到其他服务器时，在后端仓库目录执行：

```bash
git lfs pull
sudo install -d -m 755 /mnt/ai-gc/models
sudo cp -a models/. /mnt/ai-gc/models/
```

复制后按 [`models/README.md`](../models/README.md) 核对校验和。服务器上多出的 `/mnt/ai-gc/models/model_quantized.onnx` 不是当前配置使用的中文对齐模型。

## 2. 检查 Compose 配置

目标服务器运行的是 `/opt/aigc-dit/aigc-dit-be/docker-compose.yml`。当前 `master` 已包含关键配置：

```yaml
services:
  backend:
    environment:
      SILERO_VAD_ENABLED: ${SILERO_VAD_ENABLED:-true}
      SILERO_VAD_MODEL_PATH: ${SILERO_VAD_MODEL_PATH:-/mnt/ai-gc/models/silero_vad.onnx}
      FORCED_ALIGNMENT_ENABLED: ${FORCED_ALIGNMENT_ENABLED:-true}
      FORCED_ALIGNMENT_MODEL_PATH: ${FORCED_ALIGNMENT_MODEL_PATH:-/mnt/ai-gc/models/forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx}
      FORCED_ALIGNMENT_ASSETS_PATH: ${FORCED_ALIGNMENT_ASSETS_PATH:-/mnt/ai-gc/models/forced-alignment/assets}
    volumes:
      - ${DIT_DATA_ROOT:-/mnt/ai-gc}/models:/mnt/ai-gc/models:ro
```

旧版服务器 Compose 只有日志挂载，没有模型挂载和上述环境变量。发布后端时必须同时更新 Compose；只替换 JAR，容器仍读不到模型。其余阈值和采样参数已有默认值，见仓库根目录 `.env.example`。

如果需要覆盖默认值，把 `.env` 放在 `/opt/aigc-dit/aigc-dit-be/.env`，先核对现有 PostgreSQL、MinIO、JWT 等配置再添加字幕变量。没有 `.env` 且目录为 `/mnt/ai-gc` 时，当前 Compose 默认启用两套模型。`DIT_DATA_ROOT` 指服务器目录；模型在容器内仍挂载到 `/mnt/ai-gc/models`，改服务器目录时无需把容器内模型路径一起改掉。

`FFMPEG_CAPTION_BURN_ENABLED` 控制旧的同步字幕烧录路径；当前分镜字幕使用独立的异步后处理流程，不需要为了本功能把它设为 `true`。

## 3. 发布后端与前端

在本地分别从当前 `master` 构建。后端使用 `clean`，避免旧的 Flyway SQL 遗留在 `target/classes` 中；前端使用当前源码重新打包。

```bash
cd /path/to/aigc-dit-be
mvn -B clean package -pl dit-server -am -DskipTests

cd /path/to/aigc-dit-fe
npm ci
npm run build
```

在能够使用 `ssh`、`scp`、`sshpass` 的 Bash 环境中运行项目自带的发布脚本。脚本会提示输入 SSH/sudo 密码；不要把密码提交到 Git。`fnii` 用户需要有 sudo 权限。后端先发布，确认健康后再发布前端：

```bash
cd /path/to/aigc-dit-be
DEPLOY_HOST=172.171.113.250 \
DEPLOY_USER=fnii \
DEPLOY_REMOTE_ROOT=/opt/aigc-dit \
./deploy.sh --skip-build

cd /path/to/aigc-dit-fe
DEPLOY_HOST=172.171.113.250 \
DEPLOY_USER=fnii \
DEPLOY_REMOTE_ROOT=/opt/aigc-dit \
./deploy.sh --skip-build
```

后端脚本上传新 JAR、`deploy/Dockerfile.prebuilt` 和当前 Compose，备份远端旧 Compose，然后只重建 `backend`；前端脚本上传 `dist`、`nginx.conf` 和 `deploy/Dockerfile.prebuilt`，只重建 `frontend`。两者都会等待容器健康。现有 PostgreSQL、Redis、MinIO 数据目录不应被重建或清空。

Windows 的 `sshpass` 与系统 SSH 组合可能无法正确输入密码；若脚本出现 `Permission denied (publickey,password)`，先用交互式 `ssh fnii@172.171.113.250` 确认账号密码，再在可正常使用 `sshpass` 的 WSL/Linux 环境运行发布脚本。`--skip-build` 仅跳过本地编译，前提是上述 JAR 与 `dist` 已在同一工作目录构建完成。不要把 SSH 认证失败误判为后端构建或字幕模型问题。

## 4. 验证

在目标服务器检查容器与配置：

```bash
compose=/opt/aigc-dit/aigc-dit-be/docker-compose.yml
sudo docker compose -f "$compose" config -q
sudo docker compose -f "$compose" ps backend frontend
sudo docker inspect dit-backend --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}'
sudo docker exec dit-backend sh -lc 'test -r /mnt/ai-gc/models/silero_vad.onnx && test -r /mnt/ai-gc/models/forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx && test -r /mnt/ai-gc/models/forced-alignment/assets/vocab_zh.json && echo models-ok'
sudo docker exec dit-backend sh -lc 'printenv SILERO_VAD_ENABLED FORCED_ALIGNMENT_ENABLED; ffmpeg -hide_banner -filters 2>/dev/null | grep -w ass; fc-list :lang=zh family | head -n 3'
```

从浏览器或运维机器检查入口：

```bash
curl -fsS http://172.171.113.250:18080/api/health
curl -fsSI http://172.171.113.250:8088/
```

健康检查只说明服务启动。功能验收应登录前端，打开有台词且已生成视频的分镜，等待 `captionStatus` 从 `PENDING` / `RUNNING` 变为 `READY`，确认预览播放带字幕的视频。`NO_SPEECH` 表示未得到有效语音/字幕，`FAILED` 可结合后端日志和数据库 `error_message` 查因。已有分镜在工作器启动时会扫描最多 200 条，之后打开分镜板也会为尚未处理的视频入队；首次开启功能时会产生额外的音频分析和视频转码负载。

## 5. 常见问题

| 现象 | 检查点 |
| --- | --- |
| 模型在宿主机上，容器中找不到 | 更新远端 Compose，确认 `/mnt/ai-gc/models:/mnt/ai-gc/models:ro` 挂载及容器内 `test -r` 成功。 |
| 任务始终不创建 | 检查 `SILERO_VAD_ENABLED=true`、分镜是否有视频，以及后端是否使用新 JAR。 |
| `FAILED`，日志提示模型路径或 ONNX 加载错误 | 检查文件路径、读取权限、模型是否完整；查看 `sudo docker logs --tail=200 dit-backend`。 |
| `FAILED`，日志提示 FFmpeg/ASS/字体错误 | 检查镜像中的 `ffmpeg`、`ass` 滤镜和中文字体；预构建 Dockerfile 依赖原镜像提供它们。 |
| 后端启动时 Flyway 失败 | 使用当前 `master` 执行 `mvn clean package`，核对 JAR 内迁移版本；不要手工修改生产库的 `flyway_schema_history`。 |
| 后端健康但前端看不到新字幕 | 确认前端也重新构建部署，刷新分镜页面并检查对应分镜的 `captionStatus`、`captionVideoUrl`。 |

日志命令：

```bash
sudo docker compose -f /opt/aigc-dit/aigc-dit-be/docker-compose.yml logs --tail=200 backend
```

当前代码的分镜播放器使用烧录字幕视频；成片播放器使用 WebVTT 字幕轨道。分镜独立 `captions.vtt` 预览接口和前端 `<track>` 当前被注释，不应把它们当作本次验收入口。
