# 第 2 轮：云端基础设施实现说明

## 当前交付

第 2 轮提供一套可重复启动的 Cloud 开发/测试底座：Nginx、Web、API、AI Worker、一次性迁移容器、PostgreSQL 16、Redis 7，以及本地私有卷/S3 兼容存储适配器。

旧 SQLite 代码和迁移仍保留在 `dev` Profile，供后续分阶段迁移参考。Cloud 入口只扫描 `com.getjobs.cloud`，不会加载旧 Cookie、Playwright、SQLite 初始化和旧 Controller。

## 运行拓扑

```text
浏览器 -> 127.0.0.1:8080 Nginx -> Web
                              -> Cloud API

Cloud API -> PostgreSQL / Redis / 私有存储
AI Worker -> PostgreSQL / Redis / 私有存储
migrate   -> PostgreSQL（成功后 API / Worker 才启动）
```

- `edge` 网络连接 Nginx、Web 和 API。
- `data` 是内部网络，连接 PostgreSQL、Redis、迁移器、API 和 Worker。
- `worker-egress` 预留 Worker 将来的外部 AI 服务访问；本轮没有真实消费者。
- 只有 Nginx 映射 `127.0.0.1:${APP_HTTP_PORT}`。

## Profile 与进程职责

| Profile | 职责 |
| --- | --- |
| `dev` | 保留 SQLite 本地开发行为和专属迁移目录 |
| `test` | 自动化测试默认配置 |
| `cloud` | PostgreSQL、Redis、存储、Actuator 公共配置 |
| `prod` | ECS JSON 结构化日志和生产运行参数 |
| `api` | HTTP API 进程，容器内端口 8888 |
| `worker` | 独立 Worker 进程骨架，容器内端口 8889 |
| `migrate` | 使用迁移角色执行 Flyway，完成后退出 |

同一个 Boot Jar 根据 Profile 承担 `api`、`worker` 或 `migrate` 角色。迁移角色拥有 Schema 变更能力；运行时应用角色只有连接、Schema 使用和默认业务 DML/序列权限，不能执行 DDL。

API、Worker 和迁移器使用 Java 21 distroless 非 root 运行镜像（UID/GID 10001），不包含 shell、包管理器、Playwright、Chromium 或 Xvfb；容器健康检查由独立的最小 Java 探针完成。

## 数据库迁移边界

- SQLite `V1-V4` 位于 `db/migration/sqlite`，只由 `dev` 使用。
- PostgreSQL `V1` 位于 `db/migration/postgresql`，仅创建 `pgcrypto`、`citext`、`app` Schema、角色权限和默认授权。
- 本轮不创建用户或其他 SaaS 业务表，不迁移真实 SQLite 数据。
- PostgreSQL 是业务事实源；Redis 即使暂时不可用，也不应造成数据库事实丢失。

## 私有文件存储

`FileStorage` 固定提供保存、读取、存在性检查和删除。默认实现写入 Docker 私有卷，对象 Key 由服务端随机生成并做路径边界校验；没有公开文件访问 API。

生产可将 `APP_STORAGE_TYPE` 切换为 `s3` 并配置兼容 S3 的 Bucket、Region、Endpoint 和寻址方式。访问凭证只能由部署平台 Secret 或 AWS 标准凭证链注入，不得写入仓库。

## 健康、指标与日志

- `/livez` 只包含 Spring liveness state。
- `/readyz` 包含 readiness state、PostgreSQL、Redis 和存储 HealthIndicator。
- `/api/health` 是 Nginx 对 `/readyz` 的兼容代理。
- Prometheus 指标仅在内部 Actuator 端点提供。
- Nginx 丢弃不可信请求 ID 并生成可信 UUID；应用把请求 ID 写入响应头和 MDC，结束后清理线程上下文。
- 生产日志使用 ECS JSON，并限制异常堆栈长度。日志工具对密码、Authorization、Cookie 和 Token 等敏感字段做脱敏；不得记录请求体、简历正文或完整 Prompt。

## 备份边界

PostgreSQL 使用 `pg_dump` 自定义格式备份。恢复默认只能落入 `ai_jobpilot_restore_*` 临时验证数据库，主库覆盖需要显式参数和确认。

完整恢复链路必须同时覆盖：

1. PostgreSQL 定期备份和恢复演练；
2. 私有文件卷或 S3 Bucket 的版本化/备份；
3. 与两者匹配的应用版本和 Flyway 迁移。

Redis AOF 用于提高本地缓存/后续队列的耐久性，但不能替代 PostgreSQL 或私有文件备份。

## 本轮明确不做

- 不创建 SaaS 用户和业务表；
- 不实现 Session、绑定码或业务限流；
- 不实现 AI 消息生产、消费和未完成消息重新投递，该验收顺延到第 5 轮；
- 不发布公开文件接口；
- 不部署 Kubernetes、真实域名、证书或生产服务器。

启动、探针、备份和故障排查命令见 [README_DOCKER.md](README_DOCKER.md)。
