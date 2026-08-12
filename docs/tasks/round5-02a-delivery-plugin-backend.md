# 第五轮任务 2A：投递状态机、插件设备与 Token 后端

## 目标与现有基线

当前分支已在 commit `04c8ba1` 完成并验收 Cloud AI 匹配：V5 `job_matches`、可靠 Outbox、严格 AI 输出、APPLY/REVIEW/SKIP 服务端决策、岗位匹配筛选。第四轮已有 Web Session、CSRF、Redis Session、PostgreSQL RLS、岗位池。

本任务只实现第五轮第二批的后端部分：V6 投递清单、插件绑定/Token 鉴权、逐条用户确认、插件领取/租约/结果回传。不要修改 Chrome 扩展或完整前端；它们将在下一任务接入这些 API。

必须遵守项目 `CLOUD_ARCHITECTURE.md`、`CLOUD_SECURITY.md`、`CLOUD_DATABASE_DESIGN.md`、`CLOUD_API_DESIGN.md` 已确定的边界：Web Session 和插件 Token 是不同身份；插件不能读取简历、修改偏好或确认任务；服务器不接收招聘平台 Cookie/密码，不执行平台 DOM。

## 已批准的产品语义

- AI `APPLY`：自动创建一条 `PENDING_CONFIRMATION` 投递任务；绝不自动确认。
- AI `REVIEW`：不自动建任务，用户可通过 Web API 手动添加。
- AI `SKIP`：不创建任务，Web 手动添加也拒绝。
- 只支持逐条确认，不实现批量确认/批量投递 API。
- 支持平台：`BOSS`、`ZHILIAN`；其他平台本轮不可创建投递任务。
- Boss 可使用/编辑最多 60 Unicode code point 的招呼语；智联本轮不支持自定义招呼语，必须保存/返回 `null`，编辑接口返回 `GREETING_UNSUPPORTED`。
- 扩展离线时，用户确认后的任务保持 `CONFIRMED`；不由后端静默改回，也不直接执行。用户之后可再次显式唤醒扩展。

## 1. V6 增量迁移

新增 `V6__delivery_plugin_backend.sql`，不得修改 V1-V5。

### 1.1 `app.plugin_devices`

- `id uuid`、`user_id`、`device_name varchar(100)`；
- `installation_id_hash char(64)`（仅随机安装 ID 的 SHA-256，不是硬件指纹）；
- `browser_name/browser_version/extension_version`；
- `capabilities jsonb`，只允许数组，当前值只允许 `BOSS|ZHILIAN`；
- `status ACTIVE|REVOKED|DISABLED`；`last_seen_at/bound_at/revoked_at/revoke_reason/version/created_at/updated_at`；
- `UNIQUE(id,user_id)`；同一用户活动安装 ID 唯一；RLS；合理索引。

### 1.2 `app.plugin_tokens`

- `id uuid`、`user_id`、`plugin_device_id` 复合 FK；
- `token_prefix varchar(16)`；`token_hash char(64)` 唯一；严禁明文 Token 列；
- `scopes jsonb`，本轮只允许 `device:read|tasks:read|tasks:write`；
- `status ACTIVE|REVOKED|EXPIRED`；`expires_at/last_used_at/created_at/revoked_at/version`；
- RLS；索引；Token/设备/用户必须一致。

### 1.3 `app.delivery_tasks`

- UUID + `user_id`；复合 FK 到同用户 `job_posts`、可空 `job_matches`、可空 `plugin_devices`；
- `status`: `PENDING_CONFIRMATION|CONFIRMED|LEASED|EXECUTING|SUCCEEDED|FAILED|PAUSED|SKIPPED|CANCELLED`；
- `greeting varchar(60)`；`confirmation_version`、`confirmed_at/by`；
- `idempotency_key_hash char(64)`；
- `lease_id`、`leased_at`、`lease_expires_at`、`execution_id varchar(80)`、`attempt_count`；
- `last_error_code`、`last_error_message`、`last_error_retryable`、`started_at/finished_at/version/created_at/updated_at`；
- 唯一 `(id,user_id)`、用户级幂等键；同用户同岗位活动任务部分唯一（至少覆盖 PENDING_CONFIRMATION/CONFIRMED/LEASED/EXECUTING/PAUSED）；状态一致性 CHECK（确认、租约、终态时间等）；队列/列表/过期租约索引；RLS。

### 1.4 `app.delivery_task_events`

- identity 主键、`user_id`、复合 FK 到任务；
- `event_type` 白名单：至少 `CREATED|CONFIRMED|GREETING_UPDATED|CONFIRMATION_INVALIDATED|SKIPPED|LEASED|STARTED|SUCCEEDED|FAILED|PAUSED|LEASE_EXPIRED|DEVICE_REVOKED`；
- `from_status/to_status`、`actor_type USER|PLUGIN|SYSTEM`、`actor_id`、`request_id`、`event_key`、白名单 `details jsonb`、`created_at`；
- `UNIQUE(delivery_task_id,event_key)`；RLS；只允许 INSERT/SELECT 的策略，普通 app role 不得 UPDATE/DELETE。

### 1.5 自动创建 APPLY 清单

- 实现可靠、事务内的数据库触发器或等价原子机制：`job_matches` 首次转为 `SUCCEEDED + APPLY` 时，自动为同用户同岗位创建 `PENDING_CONFIRMATION` 任务和 `CREATED` 事件；重复 UPDATE/重复 Worker 完成不得重复。
- Boss 初始 greeting 使用 Match greeting（仍受 60 code point/数据库限制）；智联始终 `null`。
- REVIEW/SKIP/FAILED 不创建。
- 对迁移前已存在的最新 `SUCCEEDED + APPLY` 可做幂等回填，只为 BOSS/ZHILIAN 创建。

### 1.6 最小权限函数

至少实现并测试以下原子函数；都必须 `SECURITY DEFINER`、固定 `search_path`、`row_security=off`、参数校验、撤销 PUBLIC、只授权 `${app_role}`：

- 用 token hash 查询插件身份/设备/Scope/过期/用户状态所需的最小字段；不得返回 token hash 或其他用户资料。
- 原子 start：Token 可信 `user_id + device_id`、任务 ID、expected version、executionId、租约秒、最大尝试；仅 `CONFIRMED` 且分配为空/当前设备、设备 ACTIVE、capability 匹配时可转 `EXECUTING`，生成随机 lease、attempt+1。两设备并发只有一个成功。
- success/fail/pause：同时校验用户、设备、任务、lease、executionId、version 和未过期租约；在同事务写状态和不可变事件。`SUCCEEDED` 终态不可被后续 fail/pause 覆盖；同一 executionId + 结果幂等。
- 过期租约恢复：未满最大尝试回 `CONFIRMED`，达到上限转 `FAILED`，释放设备/lease，并写事件；不得永久卡住。

## 2. 绑定码、插件 Token 与安全链

新建清晰包 `com.getjobs.cloud.plugin`，不要复用本地旧 Controller。

### 2.1 绑定码

- `POST /api/plugin/bind-code`：Web Session + CSRF + `Idempotency-Key`；Redis 保存一次性高熵、可人工显示的绑定码，TTL 默认 5 分钟；每用户最多 3 个活动码或用新码替换旧码；请求/Redis/日志均不接受 `user_id`。
- 响应 `{ bindCode, expiresAt, expiresInSeconds }`；码只返回一次，不进数据库/审计 details/日志。
- `POST /api/plugin/bind`：匿名，只消费一次有效码；请求 `{bindCode, installationId, deviceName, browserName, browserVersion, extensionVersion, capabilities}`；验证长度、字符、支持平台和 URL/秘密字段拒绝。

### 2.2 设备与 Token

- 绑定成功进入绑定码对应用户的租户事务，创建/更新设备并签发 Token。
- Token 至少 256-bit CSPRNG，建议格式 `ajp_plg_<base64url>`；明文仅绑定响应返回一次；数据库只保存 SHA-256 十六进制 hash + prefix；日志/异常/toString 不得出现明文。
- 默认 Scope 仅 `device:read,tasks:read,tasks:write`；默认过期 90 天且可配置。
- 插件 Token 认证是独立 stateless `SecurityFilterChain`，只匹配：匿名 `/api/plugin/bind`，Bearer `/api/plugin/me`、`/api/plugin/tasks/**`。不要让插件 Token 进入 Web Session 接口，也不要让 Web Session 替代插件 Token。
- 新建不可包含邮箱/简历的 `PluginPrincipal`。认证失败映射：`PLUGIN_TOKEN_INVALID|PLUGIN_TOKEN_EXPIRED|DEVICE_REVOKED|ACCOUNT_DISABLED|FORBIDDEN`；不按错误暴露 token 是否存在。
- 验证 Scope；认证成功可降频更新 device/token lastSeen/lastUsed，不输出 Authorization。
- `GET /api/plugin/me` 只返回设备、Token scopes/expiry 和最小用户显示字段（可只返回 userId，不返回邮箱）。
- Web 设备管理：`GET /api/plugin/devices`，`POST /api/plugin/devices/{id}/revoke`。撤销时同事务撤销该设备 Token，释放/恢复未开始或过期任务租约；跨用户统一 404。

安全链实现建议：新增 `@Order(1)` Plugin chain 和 `@Order(2)` 现有 Web chain；准确的 securityMatcher 不得吞掉 `/api/plugin/bind-code` 和 `/api/plugin/devices/**` Web 接口。

## 3. Web 投递清单 API

新建 `com.getjobs.cloud.delivery` 的 Models/Repository/Service/Controller，所有 Web 业务在 `TenantContextExecutor` 中执行。

### 3.1 创建与查询

- `POST /api/delivery/tasks`：Session + CSRF + Idempotency-Key；`{jobPostId, jobMatchId?}`，不接受 status/user/device/greeting（初始 greeting 从匹配结果固定派生）；只允许 BOSS/ZHILIAN、当前用户、最新/指定 `SUCCEEDED` Match 的 APPLY 或 REVIEW；SKIP/无成功 Match 拒绝；默认 PENDING_CONFIRMATION；重复 Key 返回原结果，Key 对应不同请求返回 `IDEMPOTENCY_CONFLICT`。
- APPLY 通常已由触发器创建，返回/报告 `DUPLICATE_ACTIVE_TASK` 或复用现有活动任务，但不得新增。
- `GET /api/delivery/tasks`：分页，过滤 status（可逗号列表）、platform、keyword，排序白名单；返回 Task + Job + Match 摘要 + device + last event，明确类型。
- `GET /api/delivery/tasks/{id}`：详情与事件时间线；跨用户 404。

### 3.2 编辑、确认、跳过

- `PUT /api/delivery/tasks/{id}/greeting`：`{version,greeting}`；仅 BOSS 且 0-60 Unicode code point；PENDING_CONFIRMATION 可改；CONFIRMED 修改必须清确认并回 PENDING_CONFIRMATION，写两个领域事件；LEASED/EXECUTING/SUCCEEDED/SKIPPED/CANCELLED 不可改。智联返回 `GREETING_UNSUPPORTED`。
- `POST /api/delivery/tasks/{id}/confirm`：`{version,acknowledged:true,assignedDeviceId?}` + Idempotency-Key；只允许 PENDING_CONFIRMATION，或用户处理后重新确认 PAUSED/允许重试的 FAILED；设备必须同用户 ACTIVE 且 capability 支持平台；写 confirmedAt/by、confirmationVersion++、事件。不能批量。
- `POST /api/delivery/tasks/{id}/skip`：`{version,reason}` + Idempotency-Key；允许尚未执行的 PENDING_CONFIRMATION/CONFIRMED/PAUSED/FAILED；执行中和成功不可跳；清确认/租约并写事件。
- 所有写入使用 optimistic version；状态转换错误码清晰；API 不信任客户端提交 from/to status。

## 4. 插件任务 API

只接受 PluginPrincipal，不接受 Web Session，不接受 userId/deviceId 请求参数。

- `GET /api/plugin/tasks/pending?limit=1..20&platform=`：Scope tasks:read；只返回 Token 用户 `CONFIRMED` 且未指定设备/指定当前设备、当前 capability 支持的任务；只返回任务 ID/version、平台、受信岗位 URL、title/company、Boss greeting、confirmedAt/confirmationVersion；无简历/Match详情/Cookie。响应含 pollAfterSeconds 但本轮扩展不会后台静默轮询。
- `POST /api/plugin/tasks/{id}/start`：Scope read+write；`{version,executionId,extensionVersion,pageUrl?}` + Idempotency-Key；调用原子 start，返回 lease + 执行必要任务字段。
- `POST .../success`：`{leaseId,executionId,version,completedAt,resultCode,evidence}`；只允许 `DELIVERED|ALREADY_DELIVERED` 和白名单 evidence；幂等。
- `POST .../fail`：错误码白名单至少 `JOB_CLOSED|BUTTON_NOT_FOUND|NETWORK_ERROR|UNKNOWN_ERROR`；短脱敏 message；`retryable` 由服务端根据 code 决定或严格校验，客户端不能把验证码标普通失败。
- `POST .../pause`：原因只允许 `CAPTCHA_REQUIRED|LOGIN_REQUIRED|RISK_CONTROL|PAGE_CHANGED|USER_ACTION_REQUIRED`；释放租约，状态 PAUSED，需用户再次确认。
- 招聘平台 URL 严格验证：Boss 仅 HTTPS `*.zhipin.com` 岗位详情，智联仅 HTTPS `*.zhaopin.com` 岗位详情；不得开放任意 URL/重定向/脚本。

## 5. 岗位 API 联动与审计

- 替换 `MatchModels.DeliveryTaskStatus/Placeholder` 临时占位：`JobSummary.deliveryTaskStatus` 和 `JobDetail.deliveryTask` 使用明确的 Delivery 类型，并查询当前活动/最新任务。
- Match APPLY 自动建任务后，岗位列表/详情可看到 PENDING_CONFIRMATION；投递状态筛选暂由 `/api/delivery/tasks` 提供。
- V6 扩展 audit action CHECK 和 `AuditWriter` 白名单：至少 `PLUGIN_BIND_CODE_CREATED|PLUGIN_DEVICE_BOUND|PLUGIN_DEVICE_REVOKED|DELIVERY_TASK_CREATED|DELIVERY_TASK_CONFIRMED|DELIVERY_GREETING_UPDATED|DELIVERY_TASK_SKIPPED|PLUGIN_TASK_STARTED|PLUGIN_TASK_SUCCEEDED|PLUGIN_TASK_FAILED|PLUGIN_TASK_PAUSED`。
- 审计/领域事件只存 ID、状态、平台、稳定错误码、版本等白名单；不得存绑定码、Token、installationId、完整 URL query、招聘平台 Cookie/页面全文/简历/Prompt。

## 6. 配置

新增 `PluginProperties`/`DeliveryProperties`：绑定码 TTL、Token TTL、租约秒、最大尝试、最小扩展版本、pending limit/poll hint；安全边界和默认值。

- `.env.example` 只加非敏感配置；不得新增 Token/绑定码示例值。
- Redis 仅用于绑定码/短期状态，不替代 PostgreSQL 最终事实。
- 不需要给 Docker 容器新增 Secret；Token 不使用配置中的固定密钥。

## 7. 必须测试

### PostgreSQL/Flyway

- V1-V6 空库迁移；表/索引/CHECK/复合 FK；A/B RLS、无租户不可见；events app role UPDATE/DELETE 被拒；Token 表无明文列。
- APPLY 触发器：BOSS 有 greeting、智联 null；REVIEW/SKIP 不建；重复完成不重复；活动任务唯一。
- 原子 start 两设备竞争仅一个成功；错误设备/版本/lease/execution 拒绝；success 幂等且终态不倒退；fail/pause/过期恢复/最大尝试。

### Spring API/Security

- 绑定码 TTL/一次消费/重复消费/错误码；Token 明文只在绑定响应；数据库只含 hash；token/hash 不出现在日志或响应。
- 插件 Token 不能调用 `/api/resumes`、`/api/preferences`、Web delivery confirm；Web Session 不能调用 plugin pending/start；Scope、过期、撤销、账户禁用立即生效。
- 设备 A Token 不能访问用户 B 或同用户其他设备专属任务；跨用户所有资源 404。
- Web 任务：APPLY 自动任务、REVIEW 手动添加、SKIP 拒绝、Boss greeting 编辑重确认、智联禁编辑、逐条 confirm/skip、版本冲突、无批量端点。
- 插件 pending 只返回 CONFIRMED；两设备并发 start；重复 success；SUCCEEDED 后 fail 不倒退；pause 原因；错误码白名单；租约过期恢复。
- 使用 Testcontainers Postgres + Redis；不联网、不调用真实 AI/招聘平台。

## 验收命令

- `./gradlew.bat clean test --console=plain`
- `docker compose config --quiet`
- `git diff --check`
- `node scripts/validate-repository-hygiene.mjs`
- 确认 `rg -n "Cookie|LocalStorage|SessionStorage|password" src/main/java/com/getjobs/cloud/plugin src/main/java/com/getjobs/cloud/delivery` 没有招聘平台凭证通道（合法的安全注释除外）。

## 非目标与限制

- 不修改 `chrome-extension/**` 和 `front/**`；下一任务接入。
- 不实现插件岗位采集、后台静默轮询、批量确认、自动确认、服务器 Playwright/DOM 投递、验证码绕过、额度/支付。
- 不修改 V1-V5、SQLite、本地旧 `com.getjobs.application`。
- 不执行 Git add/commit/push/PR，不部署、不运行生产迁移、不删除文件、不读写真实 Secret。

完成报告必须逐条对应实现、测试数量/结果、跳过项和剩余风险；不得把本任务必测项顺延。
