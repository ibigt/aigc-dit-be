# aigc-dit-be

致启·光驭后端。Maven 多模块单应用，包名 `cn.aigc.dit`。

| 模块 | 职责 |
| --- | --- |
| `dit-common` | `CommonResult` / `BizException` / 分页 / JWT 用户上下文 |
| `dit-storage` | S3 兼容 SPI（Put/Get/Delete/Presign），MinIO 实现 |
| `dit-generate` | 生成 Provider SPI（`submit/poll/cancel`），默认 Mock |
| `dit-server` | 唯一可运行模块：API、JWT、Flyway、Knife4j、健康检查 |

## 环境

- JDK 17
- Maven 3.9+
- PostgreSQL 16（启动 `dit-server` 时需要）

## Docker Compose

两仓作为兄弟目录（`aigc-dit-be` 与 `aigc-dit-fe`），Linux 上先建数据目录，再一键拉起 Postgres、Redis、MinIO、backend、frontend：

```bash
bash deploy/prepare-dirs.sh
docker compose up --build
```

Windows / 不想用 `/mnt/ai-gc` 时，复制 `.env.example` 为 `.env`，改 `DIT_DATA_ROOT`（例如 `E:/data/ai-gc` 或 `./data`）。

| 服务 | 端口 |
| --- | --- |
| frontend（Nginx 静态 + `/api` 反代） | `8088`（`http://<IP>:8088/login`） |
| backend（`dit-server`，一般不必直连） | `18080` |
| Postgres | `5432` |
| Redis | `6379` |
| MinIO API / Console | `9000` / `9001` |

本机有 NVIDIA GPU 时再启 ComfyUI（无 GPU 保持 Mock）：

```bash
COMFYUI_ENABLED=true docker compose --profile comfy up --build
```

MinIO → OSS/COS 只改 `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY`。

## 构建

```bash
mvn -pl dit-server -am package
```

本地启动前准备好 Postgres（库名默认 `dit`，账号密码默认 `fnii` / `Fnii@123`），然后：

```bash
mvn -pl dit-server spring-boot:run
```

| 入口 | 说明 |
| --- | --- |
| `GET /api/health` | 业务健康检查 |
| `GET /actuator/health` | Actuator |
| `POST /api/auth/login` | 登录，默认管理员 `admin` / `Fnii@123` |
| `POST /api/auth/register` | 注册创作者 |
| `GET /api/auth/me` | 当前用户（Bearer JWT） |
| `/doc.html` | Knife4j |

配置走环境变量：`POSTGRES_*`、`REDIS_*`、`JWT_SECRET`、`S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY`。未配置 `S3_ENDPOINT` 时不装配对象存储客户端。

## 数据目录规范（Linux）

默认根目录 **`/mnt/ai-gc`**，在 `application.yml` 的 `dit.storage` 下修改：

```yaml
dit:
  storage:
    root: /mnt/ai-gc
    postgres: ${dit.storage.root}/postgres
    redis: ${dit.storage.root}/redis
    minio: ${dit.storage.root}/minio
    logs: ${dit.storage.root}/logs
```

只改 `root` 时，子目录会跟着变；某个子目录也可以写成绝对路径单独覆盖。

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `dit.storage.root` | `/mnt/ai-gc` | 统一数据根 |
| `dit.storage.postgres` | `/mnt/ai-gc/postgres` | PostgreSQL 数据 |
| `dit.storage.redis` | `/mnt/ai-gc/redis` | Redis 持久化 |
| `dit.storage.minio` | `/mnt/ai-gc/minio` | 图片、视频等对象存储 |
| `dit.storage.logs` | `/mnt/ai-gc/logs` | 应用日志 |

库表只存相对 `storage_key` / `poster_key`，对象文件进 MinIO 目录。

在服务器上先建目录，再挂载启动 Postgres：

```bash
bash deploy/prepare-dirs.sh

docker rm -f dit-postgres
docker run -d \
  --name dit-postgres \
  -e POSTGRES_USER=fnii \
  -e POSTGRES_PASSWORD='Fnii@123' \
  -e POSTGRES_DB=dit \
  -p 5432:5432 \
  -v /mnt/ai-gc/postgres:/var/lib/postgresql/data \
  --restart unless-stopped \
  postgres:16
```
