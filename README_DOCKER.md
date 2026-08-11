# Cloud Docker 一键启动指南

本指南对应第 3 轮 Cloud 用户系统。启动后只有 Nginx 绑定宿主机端口，默认入口为：

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

1. 在被 Git 忽略的 `.secrets/` 中生成 PostgreSQL 迁移角色、应用角色、Redis 密码和认证 HMAC Pepper；
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
| `redis` | Redis 7 + AOF，保存 Web Session、认证限流计数及后续队列基础设施 | 无 |

Redis 不是唯一业务事实源。用户账号和审计记录保存于 PostgreSQL；Redis Session 丢失会要求用户重新登录，但不会丢失账号数据。本轮仍不实现插件绑定码或 AI 队列消费。

## 登录与 Session

首次打开工作台会跳转到 `/login`，也可在 `/register` 注册。普通登录使用浏览器会话 Cookie，Redis 空闲过期时间为 12 小时；勾选“记住我”后 Cookie 和 Redis Session 均保持 30 天。Cookie 名为 `AJP_SESSION`，设置 `HttpOnly`、`SameSite=Lax` 和 `Path=/`，CSRF Token 只保存在页面内存并通过 `X-CSRF-TOKEN` 请求头发送。

本机 HTTP 默认 `AUTH_COOKIE_SECURE=false`。正式 HTTPS 环境必须设置：

```dotenv
AUTH_COOKIE_SECURE=true
```

修改该值后需要重建或重启 API。生产环境还应使用部署平台 Secret 提供 `auth_hash_pepper`，不可写入 `.env`、Compose 文件或 Git。

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

启用 HTTPS 时必须同时把 `AUTH_COOKIE_SECURE` 设置为 `true`。否则 Cookie 不满足正式环境安全要求；反过来，在本机纯 HTTP 下误设为 `true` 会导致浏览器不发送 Session Cookie，看起来像“登录后立刻掉线”。

该示例不是生产部署脚本，本轮不会申请证书、配置真实域名或部署服务器。

## 排查启动失败

先执行：

```bash
docker compose ps -a
docker compose logs migrate postgres redis api ai-worker web nginx
```

- `migrate` 以退出码 `0` 结束是正常现象；非零表示数据库迁移失败。
- `api` 或 `ai-worker` 不就绪时，优先检查 PostgreSQL、Redis 和私有存储权限。
- 登录页反复返回登录状态时，检查 Redis 是否健康、浏览器是否收到 `AJP_SESSION`，以及本机 HTTP 是否误设了 `AUTH_COOKIE_SECURE=true`。
- 返回 `CSRF_INVALID` 时刷新页面后重试；前端只会对安全过滤器明确拒绝的请求自动刷新 CSRF Token 并重试一次。
- 返回 `ACCOUNT_LOCKED` 时等待响应中的 `Retry-After`；连续失败锁定会从 15、30、60 分钟递增，最长 24 小时。
- 8080 被占用时，可在本机未提交的 `.env` 中设置 `APP_HTTP_PORT=8081`，然后重新启动。
- 不要把日志中的 Secret、Authorization、Cookie、Token、简历正文或完整 Prompt 发到公开 Issue。
