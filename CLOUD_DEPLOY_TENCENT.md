# AI-JobPilot-Cloud 腾讯云部署说明（P10）

本文是腾讯云 CVM + Docker Compose 的部署说明和人工验收基线。**当前仓库没有连接腾讯云、没有申请证书、没有创建安全组，也没有声称已经完成生产部署。** 文中的域名、IP、证书和 Secret 都是占位或操作说明，不能直接当作真实资源。

## 1. 目标拓扑

生产建议使用一台受控 CVM（或等价的容器主机）承载 Compose，公网只到宿主机 Nginx：

```text
用户/插件 -- HTTPS 443 --> 宿主机 Nginx -- HTTP loopback --> 127.0.0.1:8080 Compose Nginx
                                                        |--> Web
                                                        |--> API :8888（仅 Compose 网络）
                                                        |--> Worker :8889（仅 Compose 网络）
                                        data internal --> PostgreSQL :5432 / Redis :6379 / ClamAV
```

宿主机示例 `deploy/nginx/https.conf.example` 只反代到 `127.0.0.1:8080`；不要把它改成直接访问 Compose 内的 `api:8888` 或 `web:6866`。Compose 的 `nginx` 端口绑定默认是 `127.0.0.1:8080`，不是公网监听。

## 2. CVM、目录和权限

上线前由运维人工确认：

- 使用受支持的 Linux 发行版、Docker Engine 和 Compose v2；磁盘容量按 PostgreSQL、私有文件、ClamAV 病毒库、镜像和备份增长预留，并设置磁盘告警。
- 使用专用非 root 运维账号执行 Compose；Docker socket 属于高权限资源，只授予受控运维组。
- 项目目录只放代码、Compose、模板和无秘密 `.env.example`；生产 `.env`、`.secrets/`、证书私钥、备份和日志放在独立受限目录。
- `.secrets/` 目录权限使用 `0700`；Linux Compose 的文件型 Secret 是只读 bind mount，Java 容器又使用独立非 root UID，因此 Secret 文件使用 `0640`，并通过 `APP_RUNTIME_GID` 仅把运维账号主组补充给需要 Secret 的容器。不要改成 `0644`；证书私钥仍只允许 Nginx 进程/受控运维账号读取。禁止通过 Git、Issue、CI Artifact 或聊天工具传输。
- `backups/` 只供备份任务账号写入，不能位于 Nginx 静态目录；生产长期备份应转移到独立加密对象存储。
- `private-storage` 只通过容器卷或私有 S3 Bucket 访问，不能映射到公开 Web 目录。

### Ubuntu CVM 安装 Docker Engine 与 Compose plugin

以下是运维在 Ubuntu CVM 上按 Docker 官方文档执行的 apt 仓库方式；本文没有代替运维执行安装。完整步骤以 [Docker Engine on Ubuntu 官方文档](https://docs.docker.com/engine/install/ubuntu/) 为准：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker --version
docker compose version
sudo docker run --rm hello-world
```

Docker 发布端口可能绕过 UFW 规则，不能只依据 `ufw status` 判断端口安全；必须同时复核腾讯云安全组、Docker 发布配置和宿主机 `DOCKER-USER` 链（按实际 iptables/nftables 实现检查），并确认没有把数据库、Redis、API、Worker 或 Web 端口暴露到公网。

## 3. Secret 与环境变量契约

先复制非敏感示例并逐项审阅：

```bash
cp .env.example .env
# Linux 必须把示例值替换为当前非 root 运维账号的主组 GID。
secret_gid="$(id -g)"
sed -i "s/^APP_RUNTIME_GID=.*/APP_RUNTIME_GID=${secret_gid}/" .env
chmod 700 .secrets
chmod 640 .secrets/*
docker compose config
```

用 `docker compose config` 复核 `migrate`、`api`、`ai-worker` 的 `group_add` 等于上述 GID。若 Secret 已在外部 Secret Manager 中按容器 UID/GID 安全投递，应以该平台的权限模型为准，不能为绕过报错扩大成全局可读。

生产优先使用 Docker Secret/KMS/Secret Manager 注入以下文件名：

| Secret | 用途 | 是否可写入 `.env.example` |
| --- | --- | --- |
| `db_owner_password` | PostgreSQL 迁移/owner 角色 | 否，必须为空/由 Secret 注入 |
| `db_app_password` | `jobpilot_app` 运行角色 | 否，必须为空/由 Secret 注入 |
| `redis_password` | Redis 密码 | 否，必须为空/由 Secret 注入 |
| `auth_hash_pepper` | 用户密码哈希 Pepper | 否，必须为空/由 Secret 注入 |
| `data_encryption_key` | AES-256-GCM 简历数据 Key | 否，必须为空/由 Secret/KMS 注入 |
| `ai_api_key` | Worker 调用 AI 供应商 | 否，必须为空/由 Secret 注入 |

必须保持的项目差异：

- 本项目使用 Redis Session 和 `AJP_SESSION` Cookie，不使用 JWT；不要添加未消费的 `JWT_SECRET`。
- 插件 Token 是高熵随机值，数据库只存 SHA-256 哈希；不需要 `PLUGIN_TOKEN_SECRET`，不要为了满足外部命名表机械加入它。
- 管理员角色由数据库中的受控角色和人工流程管理；不允许通过环境变量密码自动把普通用户提权。
- `AI_BASE_URL`、`AI_MODEL` 是非敏感运行参数，Worker 在 `application-worker.yaml` 中消费；AI Key 仍只走 Secret/空占位。
- `APP_STORAGE_LOCAL_ROOT` 在 `APP_STORAGE_TYPE=local` 时消费，默认是容器内 `/var/lib/ai-jobpilot/private`；切换 S3 后由 Bucket/Region/Endpoint 配置决定实际对象位置。
- 当前代码没有消费 `APP_PUBLIC_URL`；公网 URL 由宿主机 Nginx 的真实 `server_name`、Host 和 HTTPS 提供，不要添加未使用的伪变量。
- 容器生产 Profile 使用 ECS（Elastic Common Schema）格式的 stdout/stderr 控制台日志，`application-prod.yaml` 关闭文件日志；本地旧版的 `APP_LOG_DIR`/`logging.file.name` 不应被当作云端公开日志目录。

## 4. 启动与健康检查

本地/预发布可使用仓库脚本验证完整 Compose：

```bash
./start_docker.sh
docker compose ps
curl -i http://127.0.0.1:8080/livez
curl -i http://127.0.0.1:8080/readyz
curl -i http://127.0.0.1:8080/api/health
```

生产启动前必须先完成 Secret、`.env`、证书和安全组人工复核，再执行：

```bash
docker compose config
docker compose up -d --build
docker compose ps
```

`migrate` 正常退出码为 `0`；API/Worker/Web/Nginx 必须健康。任何依赖不就绪、Secret 缺失或 `AUTH_COOKIE_SECURE` 未确认时都停止上线，不通过“先启动再补安全”绕过检查。

## 5. 宿主机 Nginx、域名和 HTTPS

1. 将 `app.example.com` 替换为经确认的正式域名；示例中的域名和 `/etc/letsencrypt/...` 路径不是实际证书。
2. 由可信 CA 签发证书，证书私钥只读挂载到宿主机 Nginx；配置续期成功、到期和撤销告警。
3. 80 端口只做 301 跳转到 443；443 使用 TLS 1.2/1.3、HSTS 和安全响应头。
4. 所有 API/Web 位置反代到 `http://127.0.0.1:8080`。宿主机 Nginx 对 `X-Real-IP`/`X-Forwarded-For` 使用真实远端地址，Compose 内层仅接受经过校验的单一客户端 IP。
5. 正式 HTTPS 必须在生产 `.env` 设置：

```dotenv
AUTH_COOKIE_SECURE=true
```

`AUTH_COOKIE_SECURE=false` 只允许用于回环地址本地 HTTP。未验证 HTTPS Cookie 能正常登录、续期、退出前，不得接入真实用户。

## 6. 腾讯云安全组与网络边界

安全组上线前人工核对为；控制台入口与规则语义以[腾讯云 CVM 安全组官方文档](https://cloud.tencent.com/document/product/213)为准，本文没有代替运维创建或修改任何云资源：

| 端口 | 公网策略 | 用途 |
| --- | --- | --- |
| TCP 80 | 允许 | HTTP 到 HTTPS 跳转 |
| TCP 443 | 允许 | HTTPS 正式入口 |
| TCP 22 | 仅固定管理 IP/CIDR | SSH 运维；禁止 `0.0.0.0/0` |
| TCP 5432、6379 | 禁止公网 | PostgreSQL/Redis 只在 Compose `data` 内部网络 |
| TCP 8888、8889、6866 | 禁止公网 | API、Worker、Web 只在容器网络 |
| TCP 8080 | 禁止公网 | Compose 入口只绑定宿主机回环地址 |

同时检查 CVM 本机防火墙、Docker 发布端口、云负载均衡监听器和 IPv6 规则；任何一层暴露 5432/6379/8888/8889/6866/8080 都视为上线阻塞项。

## 7. 日志、监控与告警

- API/Worker 生产日志走 stdout/stderr，Docker 日志配置必须设置大小/文件数量轮转；不要把简历全文、Prompt、Authorization、Cookie、密码或 AI Key 写入日志。
- Nginx 使用结构化访问日志，过滤 Authorization/Cookie；错误日志和应用异常消息只保留脱敏类型/错误码。
- 监控 `/livez`、`/readyz`、容器重启、磁盘、PostgreSQL/Redis、ClamAV、AI 错误率、队列积压、登录失败、插件异常速率和备份任务。
- 备份失败、证书临期、Secret/KMS 读取失败、磁盘不足和安全组漂移必须告警；没有告警接收人和演练记录时不能判定生产就绪。

## 8. 回滚与停用

- 保留上一个已验证镜像 tag、Compose 配置、Flyway 版本和前端静态资源校验值；不要用 `latest` 作为回滚依据。
- 代码/镜像回滚前停止 API/Worker 写入并评估数据库迁移是否可逆；不可逆迁移不得仅靠切回旧镜像。
- 发现 Token 泄露、跨用户访问、数据泄露、AI Key 泄露或插件风控异常时，立即停止插件执行开关、撤销设备/Session、保留脱敏证据并启动事件响应。
- `docker compose down -v` 会删除数据卷，生产回滚禁止使用，除非已完成独立备份、双人复核和破坏性操作批准。

## 9. 上线边界

本说明完成不代表已经部署。必须同时通过 [CLOUD_LAUNCH_CHECKLIST.md](CLOUD_LAUNCH_CHECKLIST.md)、[CLOUD_BACKUP_RECOVERY.md](CLOUD_BACKUP_RECOVERY.md) 和 [CLOUD_PRIVACY_CHECKLIST.md](CLOUD_PRIVACY_CHECKLIST.md) 中的人工项；未部署、未做独立恢复演练、法律文件未定稿或账号级删除未实现时，只能做合成账号/内部小范围验证，不能邀请真实用户。
