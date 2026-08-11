# Cloud Docker 一键启动指南

本指南对应第 2 轮云端基础设施。启动后只有 Nginx 绑定宿主机端口，默认入口为：

```text
http://localhost:8080
```

Nginx 将 `/api/**` 转发到 Cloud API，其余请求转发到独立 Web 容器。PostgreSQL、Redis、AI Worker 和 Actuator 均不映射宿主机端口。

## 前置条件

- Docker Desktop（Windows/macOS）或 Docker Engine + Compose v2（Linux）
- Git
- 首次构建需要联网下载镜像和依赖

不需要在宿主机单独安装 Java、Node.js、PostgreSQL 或 Redis。

## 一键启动

Windows 在项目根目录双击 `start_docker.bat`，或执行：

```powershell
.\start_docker.ps1
```

macOS / Linux 在项目根目录执行：

```bash
chmod +x start_docker.sh
./start_docker.sh
```

启动脚本会自动：

1. 在被 Git 忽略的 `.secrets/` 中生成 PostgreSQL 迁移角色、应用角色和 Redis 的随机密码；
2. 校验 Compose 配置并构建镜像；
3. 等待 PostgreSQL、Redis 和一次性 Flyway 迁移完成；
4. 启动 API、AI Worker、Web 和 Nginx，并等待全部健康；
5. 验证 `http://localhost:8080/api/health` 返回 `UP`。

第一次构建通常较慢，之后会复用 Docker 缓存。请勿提交 `.secrets/`、`.env`、证书、备份或数据卷。

## 组件与端口

| 组件 | 作用 | 宿主机端口 |
| --- | --- | --- |
| `nginx` | 统一入口、路由、请求 ID、安全头、限流 | `127.0.0.1:8080` |
| `web` | 非 root 静态 Web 服务 | 无 |
| `api` | 隔离的 Cloud API 进程 | 无 |
| `ai-worker` | AI Worker 进程骨架；本轮不消费真实消息 | 无 |
| `migrate` | 一次性 Flyway PostgreSQL 迁移 | 无 |
| `postgres` | PostgreSQL 16，业务事实源 | 无 |
| `redis` | Redis 7 + AOF，缓存/后续队列基础设施 | 无 |

Redis 不是唯一业务事实源。本轮不实现 Session、绑定码、业务限流或 AI 队列消费，这些能力将在后续轮次接入。

## 健康检查

```bash
curl -i http://localhost:8080/livez
curl -i http://localhost:8080/readyz
curl -i http://localhost:8080/api/health
```

- `/livez`：只判断 API 进程是否存活，不依赖 PostgreSQL、Redis 或文件存储。
- `/readyz`：检查 PostgreSQL、Redis 和当前私有文件存储；任一依赖故障时返回 HTTP 503。
- `/api/health`：兼容别名，与 `/readyz` 含义相同。
- `/actuator/prometheus`：仅容器内部网络可访问，Nginx 明确拒绝外部访问。

## 常用命令

在项目根目录执行：

```bash
docker compose ps
docker compose logs -f
docker compose logs -f api ai-worker
docker compose restart api ai-worker
docker compose down
```

`docker compose down` 会停止容器，但保留 PostgreSQL、Redis 和私有文件卷。不要随意执行 `docker compose down -v`，因为 `-v` 会删除本地数据卷。

修改 Java、前端、Dockerfile 或 Compose 后，重新运行一键启动脚本即可重建。

## PostgreSQL 备份与恢复演练

Windows 备份：

```powershell
.\scripts\backup_postgres.ps1
```

Windows 恢复到明确命名的临时验证库：

```powershell
.\scripts\restore_postgres.ps1 -BackupPath backups\ai-jobpilot-YYYYMMDD-HHMMSS.dump -TargetDatabase ai_jobpilot_restore_verification -Confirm:$false
```

macOS / Linux：

```bash
./scripts/backup_postgres.sh
./scripts/restore_postgres.sh backups/ai-jobpilot-YYYYMMDD-HHMMSS.dump ai_jobpilot_restore_verification
```

恢复脚本默认只接受 `ai_jobpilot_restore_*` 临时库。覆盖主库必须使用显式生产参数并再次确认。数据库备份之外，还应独立备份 `private-storage` 私有文件卷；Redis 不进入唯一恢复链路。

## HTTPS 生产示例

`deploy/nginx/https.conf.example` 展示了精确域名、80 跳转 443、TLS 1.2/1.3、HSTS 和证书挂载路径。上线前必须替换 `app.example.com`，由可信 CA 签发证书，并在部署平台使用 Secret/只读挂载提供证书。

该示例不是生产部署脚本，本轮不会申请证书、配置真实域名或部署服务器。

## 排查启动失败

先执行：

```bash
docker compose ps -a
docker compose logs migrate postgres redis api ai-worker web nginx
```

- `migrate` 以退出码 `0` 结束是正常现象；非零表示数据库迁移失败。
- `api` 或 `ai-worker` 不就绪时，优先检查 PostgreSQL、Redis 和私有存储权限。
- 8080 被占用时，可在本机未提交的 `.env` 中设置 `APP_HTTP_PORT=8081`，然后重新启动。
- 不要把日志中的 Secret、Authorization、Cookie、Token、简历正文或完整 Prompt 发到公开 Issue。
