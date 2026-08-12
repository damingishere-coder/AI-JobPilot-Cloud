# AI-JobPilot-Cloud 第一版 API 设计

本文定义第一版 HTTP API 契约。它用于后续 OpenAPI、Controller、鉴权和集成测试设计，本轮不实现接口。

## 1. 通用约定

### 1.1 基础规则

- 基础路径：`/api`；生产环境只允许 HTTPS。
- JSON 使用 `application/json; charset=utf-8`，简历上传使用 `multipart/form-data`。
- 资源 ID 使用 UUID；时间使用 ISO 8601 UTC，例如 `2026-08-11T08:00:00Z`。
- 分页统一使用 `page`（从 1 开始）和 `size`（默认 20，最大 100）。
- 所有请求返回或透传 `X-Request-Id`，方便审计和排障。
- 客户端不得提交 `user_id`。后端只从 Web Session 或插件 Token 得到可信 `user_id`。
- 创建、确认、领取、结果回传等接口支持 `Idempotency-Key`；同一用户/设备下相同 Key + 相同请求返回同一结果，不同请求返回冲突。
- 状态变更使用 `version` 做乐观锁；过期版本返回 `409 RESOURCE_VERSION_CONFLICT`。

### 1.2 统一响应

成功：

```json
{
  "success": true,
  "data": {},
  "requestId": "req_01..."
}
```

分页：

```json
{
  "success": true,
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "total": 0,
    "hasNext": false
  },
  "requestId": "req_01..."
}
```

失败：

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请求参数不正确",
    "fieldErrors": [{ "field": "email", "reason": "格式不正确" }],
    "retryable": false
  },
  "requestId": "req_01..."
}
```

错误消息不得包含密码、Session、插件 Token、Cookie、简历全文、完整 Prompt、模型密钥或内部堆栈。

### 1.3 鉴权类型

| 类型 | 方式 | 适用接口 |
| --- | --- | --- |
| Web Session | `HttpOnly; Secure; SameSite=Lax/Strict` Cookie | Web 后台接口 |
| CSRF | Cookie 会话的 POST/PUT/DELETE 请求携带 CSRF Header | 所有 Web 状态变更接口 |
| 插件 Token | `Authorization: Bearer <opaque-token>` | `/api/plugin/**` 的绑定后接口 |
| 一次性绑定码 | 短期、高熵、单次使用 | `POST /api/plugin/bind`，不是长期 Token |

插件 Token 使用 Scope：`device:read`、`jobs:capture`、`tasks:read`、`tasks:write`。插件不能调用简历、求职目标、额度、管理或 Web 登录接口。

### 1.4 通用错误码

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 字段格式、范围或状态参数错误 |
| 400 | `MALFORMED_JSON` | JSON 无法解析 |
| 401 | `AUTH_REQUIRED` | Web 未登录 |
| 401 | `INVALID_CREDENTIALS` | 邮箱或密码错误；不区分账号是否存在 |
| 401 | `PLUGIN_TOKEN_INVALID` | 插件 Token 无效或格式错误 |
| 401 | `PLUGIN_TOKEN_EXPIRED` | 插件 Token 已过期 |
| 403 | `CSRF_INVALID` | Web 状态变更缺少有效 CSRF |
| 403 | `FORBIDDEN` | 权限或 Scope 不足 |
| 403 | `DEVICE_REVOKED` | 插件设备已撤销/禁用 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或不属于当前用户，统一返回以避免枚举 |
| 409 | `RESOURCE_VERSION_CONFLICT` | 乐观锁版本冲突 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一幂等键对应不同请求 |
| 409 | `INVALID_STATE_TRANSITION` | 当前状态不允许本次操作 |
| 413 | `PAYLOAD_TOO_LARGE` | 文件或批量请求过大 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 不支持的简历类型 |
| 422 | `BUSINESS_RULE_VIOLATION` | 参数合法但不符合业务规则 |
| 429 | `RATE_LIMITED` | 触发 IP、用户或设备限流 |
| 429 | `QUOTA_EXCEEDED` | 当前资源额度不足 |
| 500 | `INTERNAL_ERROR` | 未预期错误，返回追踪 ID |
| 503 | `DEPENDENCY_UNAVAILABLE` | PostgreSQL、Redis、对象存储或 AI 服务不可用 |

## 2. 用户系统

### `POST /api/auth/register`

- **请求参数**：JSON `{ "email": "user@example.com", "password": "...", "acceptTerms": true }`。邮箱标准化；密码长度与泄露密码策略由服务端校验。
- **响应结构**：`201`，`data: { user: { id, emailMasked, status, createdAt }, session: { expiresAt }, csrfToken }`，同时设置 Web Session Cookie。
- **权限要求**：匿名；按 IP、邮箱标准化哈希和设备特征限流。
- **主要错误码**：`VALIDATION_ERROR`、`EMAIL_ALREADY_REGISTERED`(409)、`TERMS_NOT_ACCEPTED`(422)、`RATE_LIMITED`。
- **user_id 隔离**：注册阶段不适用；事务内创建 `users` 和该用户默认额度，邮箱全局唯一。
- **插件 Token**：不允许。

### `POST /api/auth/login`

- **请求参数**：JSON `{ "email": "user@example.com", "password": "...", "rememberMe": false }`。
- **响应结构**：`200`，`data: { user: { id, emailMasked, status }, session: { expiresAt }, csrfToken }`；轮换 Session ID。
- **权限要求**：匿名；已登录时也重新认证和轮换会话。
- **主要错误码**：`INVALID_CREDENTIALS`、`ACCOUNT_LOCKED`(423)、`ACCOUNT_DISABLED`(403)、`RATE_LIMITED`。
- **user_id 隔离**：只通过标准化邮箱定位账号，成功后由服务端建立该用户上下文；不接受 `user_id`。
- **插件 Token**：不允许。

### `POST /api/auth/logout`

- **请求参数**：无 JSON；需 CSRF Header。
- **响应结构**：`200`，`data: { loggedOut: true }`；删除 Cookie 并使 Redis Session 立即失效。
- **权限要求**：Web Session；接口应允许重复退出并保持幂等。
- **主要错误码**：`CSRF_INVALID`、`RATE_LIMITED`；失效会话可仍返回成功。
- **user_id 隔离**：仅注销当前 Session，不撤销用户其他设备或插件 Token。
- **插件 Token**：不允许。

### `GET /api/me`

- **请求参数**：无。
- **响应结构**：`200`，`data: { id, emailMasked, status, profile, quotaSummary, sessionExpiresAt, csrfToken }`。
- **权限要求**：Web Session。
- **主要错误码**：`AUTH_REQUIRED`、`ACCOUNT_DISABLED`、`RATE_LIMITED`。
- **user_id 隔离**：只按 Session 中的 `user_id` 查询，绝不按 Query 参数切换用户。
- **插件 Token**：不允许；插件身份使用 `/api/plugin/me`。

## 3. 简历

### `POST /api/resumes/upload`

- **请求参数**：`multipart/form-data`：`file`（PDF、DOCX、TXT）、可选 `setCurrent=true`，必须携带 `Idempotency-Key`。服务端先执行 ClamAV 扫描，再校验魔数、MIME、扩展名、10 MiB 大小、PDF 页数和 DOCX 解压边界。
- **响应结构**：`202`，`data: { resume: { id, originalFilename, contentType, fileSize, parseStatus: "UPLOADED", current, version, createdAt }, deduplicated }`。解析由 Worker 异步进行。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`UNSUPPORTED_MEDIA_TYPE`、`PAYLOAD_TOO_LARGE`、`FILE_SIGNATURE_INVALID`(422)、`MALWARE_SUSPECTED`(422)、`QUOTA_EXCEEDED`、`STORAGE_UNAVAILABLE`(503)。
- **user_id 隔离**：存储键由服务端生成，数据库写当前 `user_id`；设置当前简历在同一事务完成。
- **插件 Token**：不允许。

### `GET /api/resumes`

- **请求参数**：Query `page`、`size`，页大小最大 100。
- **响应结构**：`200`，返回当前用户未删除简历的分页元数据，不返回提取文本。
- **权限要求**：Web Session。
- **user_id 隔离**：列表与计数均使用当前 Session 用户并受 RLS 二次约束。
- **插件 Token**：不允许。

### `GET /api/resumes/current`

- **请求参数**：无；可选 `includeExtractedText=false`，第一版默认不返回全文。
- **响应结构**：`200`，`data: { id, originalFilename, contentType, fileSize, parseStatus, parseMessage, isCurrent, createdAt, updatedAt }`；没有当前简历时 `data: null`。
- **权限要求**：Web Session。
- **主要错误码**：`AUTH_REQUIRED`、`RATE_LIMITED`、`STORAGE_UNAVAILABLE`（仅需要临时下载时）。
- **user_id 隔离**：查询 `WHERE user_id=current AND is_current=true AND deleted_at IS NULL`。
- **插件 Token**：不允许，防止插件读取简历。

### `DELETE /api/resumes/:id`

- **请求参数**：Path `id`；必须使用 `If-Match` 传当前版本，并携带 `Idempotency-Key`。
- **响应结构**：`202`，`data: { id, deletionStatus: "SCHEDULED", deletedAt }`。后台删除原文件和派生文本。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`RESOURCE_VERSION_CONFLICT`、`RESUME_IN_USE`(409)、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：用 `user_id + id` 更新；传入其他用户 ID 也返回 `RESOURCE_NOT_FOUND`。
- **插件 Token**：不允许。

## 4. 求职目标

### `GET /api/preferences`

- **请求参数**：无。
- **响应结构**：`200`，`data: { id, version, targetTitles, cities, salaryMinK, salaryMaxK, experienceLevels, degreeLevels, industries, companyScales, preferredCompanies, excludedCompanies, excludedKeywords, extraFilters, updatedAt }`；未配置时 `data: null`。
- **权限要求**：Web Session。
- **主要错误码**：`AUTH_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：只读当前用户 `is_current=true` 版本。
- **插件 Token**：不允许。

### `PUT /api/preferences`

- **请求参数**：上述求职目标字段 + `version`；数组有数量和元素长度限制，薪资范围由服务端校验。
- **响应结构**：`200`，返回新的完整当前配置；若采用历史版本，返回递增后的 `version` 和新 `id`。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`VALIDATION_ERROR`、`RESOURCE_VERSION_CONFLICT`、`PREFERENCE_LIMIT_EXCEEDED`(422)、`RATE_LIMITED`。
- **user_id 隔离**：只更新/新增当前用户版本；同一事务关闭旧 `is_current` 并创建新版本。
- **插件 Token**：不允许。

## 5. 插件绑定

### `POST /api/plugin/bind-code`

- **请求参数**：JSON `{ "deviceNameHint": "我的 Edge" }`，需 `Idempotency-Key`。
- **响应结构**：`201`，`data: { bindCode: "ABCD-EFGH", expiresAt, expiresInSeconds: 300 }`。同一用户活动绑定码数量受限。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`AUTH_REQUIRED`、`RATE_LIMITED`、`TOO_MANY_ACTIVE_BIND_CODES`(429)、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：绑定码在 Redis 关联当前 Session 的 `user_id`，不允许调用方指定。
- **插件 Token**：不允许；这是 Web 生成绑定码的接口。

### `POST /api/plugin/bind`

- **请求参数**：匿名 JSON `{ "bindCode", "installationId", "deviceName", "browserName", "browserVersion", "extensionVersion", "capabilities": ["BOSS","ZHILIAN"] }`。`installationId` 是插件随机生成的安装 ID，不是硬件指纹。
- **响应结构**：`201`，`data: { device: { id, deviceName, status, capabilities, boundAt }, token: { value, expiresAt, scopes } }`。Token 明文仅返回一次。
- **权限要求**：有效、未过期、未使用的一次性绑定码；按 IP 和绑定码限流。
- **主要错误码**：`BIND_CODE_INVALID`(401)、`BIND_CODE_EXPIRED`(401)、`BIND_CODE_USED`(409)、`EXTENSION_VERSION_UNSUPPORTED`(426)、`DEVICE_LIMIT_EXCEEDED`(422)、`RATE_LIMITED`。
- **user_id 隔离**：从 Redis 绑定码得到 `user_id`，在同一事务创建设备和 Token 哈希；请求不能传 `user_id`。
- **插件 Token**：绑定前不需要，已有插件 Token 也不能替代绑定码。

### `GET /api/plugin/me`

- **请求参数**：无。
- **响应结构**：`200`，`data: { user: { id, displayName }, device: { id, deviceName, status, capabilities, extensionVersion, lastSeenAt }, token: { scopes, expiresAt } }`。不返回邮箱全文或 Token。
- **权限要求**：插件 Token，Scope `device:read`。
- **主要错误码**：`PLUGIN_TOKEN_INVALID`、`PLUGIN_TOKEN_EXPIRED`、`DEVICE_REVOKED`、`FORBIDDEN`、`EXTENSION_UPGRADE_REQUIRED`(426)。
- **user_id 隔离**：只返回 Token 哈希记录绑定的用户和设备。
- **插件 Token**：允许，且仅允许插件 Token；Web 设备列表未来使用单独接口。

## 6. 岗位池

### `POST /api/plugin/jobs/capture`

- **请求参数**：JSON `{ "captureId", "capturedAt", "platform", "job": { "externalJobId", "title", "companyName", "salaryText", "location", "experience", "degree", "description", "jobUrl", "companyInfo", "skills", "welfare" } }`；需 `Idempotency-Key`。
- **响应结构**：`200/201`，`data: { job: { id, platform, title, companyName, createdAt, lastSeenAt }, deduplicated, analysis: { queued, matchId } }`。
- **权限要求**：插件 Token，Scope `jobs:capture`，平台必须在设备 capability 和服务器支持列表中。
- **主要错误码**：`VALIDATION_ERROR`、`UNSUPPORTED_PLATFORM`(422)、`UNTRUSTED_JOB_URL`(422)、`PAYLOAD_TOO_LARGE`、`QUOTA_EXCEEDED`、`IDEMPOTENCY_CONFLICT`、`DEVICE_REVOKED`。
- **user_id 隔离**：由 Token 注入 `user_id` 和 `source_device_id`；以 `user_id + platform + externalJobId/fingerprint` 去重。
- **插件 Token**：允许，且仅允许插件 Token。

### `POST /api/plugin/jobs/batch-capture`

- **请求参数**：JSON `{ "captureId", "capturedAt", "platform", "jobs": [...] }`；第一版每批建议最多 50 条、压缩后请求体仍受上限；每条可含 `itemKey`。
- **响应结构**：`200`，`data: { accepted, created, updated, rejected, items: [{ itemKey, jobId, status, error? }] }`。单条失败不回滚整批，但批次自身幂等。
- **权限要求**：插件 Token，Scope `jobs:capture`。
- **主要错误码**：批次级 `PAYLOAD_TOO_LARGE`、`UNSUPPORTED_PLATFORM`、`RATE_LIMITED`、`QUOTA_EXCEEDED`、`IDEMPOTENCY_CONFLICT`；条目级 `VALIDATION_ERROR`、`UNTRUSTED_JOB_URL`。
- **user_id 隔离**：批内所有岗位强制使用 Token 的 `user_id` 和设备 ID，不接受条目级用户字段。
- **插件 Token**：允许，且仅允许插件 Token。

### `GET /api/jobs`

- **请求参数**：Query `page`、`size`、`platform`、`status`、`keyword`、`capturedFrom`、`capturedTo`、`sort`（白名单字段）。`matchDecision` 和 `minScore` 随阶段 5 AI 匹配实现。
- **响应结构**：分页 `items`，每项 `{ id, platform, title, companyName, salary, location, status, latestMatchSummary, deliveryTaskStatus, lastSeenAt }`。
- **权限要求**：Web Session。
- **主要错误码**：`VALIDATION_ERROR`、`AUTH_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：所有列表、计数和关联子查询首要条件都是当前 `user_id`。
- **插件 Token**：不允许；插件任务使用专用最小字段接口。

### `GET /api/jobs/:id`

- **请求参数**：Path `id`。
- **响应结构**：`200`，`data: { id, platform, externalJobId, title, companyName, salary, location, experience, degree, description, jobUrl, companyInfo, skills, welfare, status, capturedAt, latestMatch, deliveryTask }`。
- **权限要求**：Web Session。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`AUTH_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：按 `user_id + id` 查询，并以复合条件加载 Match/Task。
- **插件 Token**：不允许。

## 7. AI 匹配

### `POST /api/jobs/:id/analyze`

- **请求参数**：Path `id`；JSON `{ "force": false, "resumeId": null, "preferenceId": null }`。空 ID 表示当前版本；`force=true` 仍受额度和频率限制；需 `Idempotency-Key`。
- **响应结构**：`202`，`data: { matchId, jobId, status: "PENDING", queuedAt, reusedExisting: false }`。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`RESUME_REQUIRED`(422)、`PREFERENCES_REQUIRED`(422)、`ANALYSIS_ALREADY_PENDING`(409)、`QUOTA_EXCEEDED`、`DEPENDENCY_UNAVAILABLE`、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：岗位、简历和偏好必须同时属于当前用户；队列消息带服务端生成的 `user_id`。
- **插件 Token**：不允许；采集后自动入队由服务器内部逻辑触发，不授予插件 AI 权限。

### `POST /api/jobs/batch-analyze`

- **请求参数**：JSON `{ "jobIds": ["uuid"], "force": false }`，第一版最多 50 条；需 `Idempotency-Key`。
- **响应结构**：`202`，`data: { accepted, reused, rejected, items: [{ jobId, matchId, status, error? }] }`。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：批次级 `VALIDATION_ERROR`、`PAYLOAD_TOO_LARGE`、`QUOTA_EXCEEDED`、`RATE_LIMITED`；条目级 `RESOURCE_NOT_FOUND`、`ANALYSIS_ALREADY_PENDING`。
- **user_id 隔离**：先用 `WHERE user_id=current AND id IN (...)` 取交集；不泄露哪些缺失 ID 属于其他用户。
- **插件 Token**：不允许。

### `GET /api/jobs/:id/match`

- **请求参数**：Path `id`；可选 Query `matchId`，不传则返回最新匹配。
- **响应结构**：`200`，`data: { id, jobId, resumeId, preferenceId, status, score, decision, summary, strengths, risks, greeting, model: { provider, name, promptVersion }, usage, error, createdAt, completedAt }`。`PENDING/PROCESSING` 字段可为空。
- **权限要求**：Web Session。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`MATCH_NOT_FOUND`(404)、`AUTH_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：岗位和 Match 都用相同当前 `user_id` 查询。
- **插件 Token**：不允许，插件只收到最终确认后的招呼语和执行字段。

## 8. 投递清单

### `POST /api/delivery/tasks`

- **请求参数**：JSON `{ "jobPostId", "jobMatchId": null, "greeting": "..." }`；需 `Idempotency-Key`。创建后默认 `PENDING_CONFIRMATION`，不能通过请求直接设为已确认。
- **响应结构**：`201`，`data: { id, jobPostId, jobMatchId, status: "PENDING_CONFIRMATION", greeting, version, createdAt }`。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`DUPLICATE_ACTIVE_TASK`(409)、`GREETING_TOO_LONG`(422)、`IDEMPOTENCY_CONFLICT`、`QUOTA_EXCEEDED`（若任务计费）。
- **user_id 隔离**：Job、Match 和新 Task 必须是当前用户；数据库复合外键再次约束。
- **插件 Token**：不允许，插件不能创建或确认投递意图。

### `GET /api/delivery/tasks`

- **请求参数**：Query `page`、`size`、`status`（可重复）、`platform`、`createdFrom`、`createdTo`、`keyword`、`sort`。
- **响应结构**：分页 `items`，每项 `{ id, status, greeting, version, confirmedAt, job: { id, platform, title, companyName, jobUrl }, match: { score, decision }, device, lastEvent, createdAt, updatedAt }`。
- **权限要求**：Web Session。
- **主要错误码**：`VALIDATION_ERROR`、`AUTH_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：任务、岗位、匹配、设备和事件聚合均限定当前 `user_id`。
- **插件 Token**：不允许；插件列表使用 `/api/plugin/tasks/pending`。

### `POST /api/delivery/tasks/:id/confirm`

- **请求参数**：Path `id`；JSON `{ "version", "acknowledged": true, "assignedDeviceId": null }`；需 `Idempotency-Key`。可确认 `PENDING_CONFIRMATION`；用户处理后可重新确认 `PAUSED` 或允许重试的 `FAILED`。
- **响应结构**：`200`，`data: { id, status: "CONFIRMED", confirmationVersion, confirmedAt, assignedDeviceId, version }`。
- **权限要求**：Web Session + CSRF，必须有明确用户动作，后台任务不得代调。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`CONFIRMATION_REQUIRED`(422)、`INVALID_STATE_TRANSITION`、`RESOURCE_VERSION_CONFLICT`、`DEVICE_NOT_AVAILABLE`(422)、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：任务和可选设备都必须属于当前用户；写入 `confirmed_by=current_user_id` 和不可变事件。
- **插件 Token**：不允许，插件绝不能替用户确认。

### `POST /api/delivery/tasks/:id/skip`

- **请求参数**：Path `id`；JSON `{ "version", "reason": "NOT_INTERESTED" }`；需 `Idempotency-Key`。
- **响应结构**：`200`，`data: { id, status: "SKIPPED", finishedAt, version }`。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`INVALID_STATE_TRANSITION`（执行中/已成功不可跳过）、`RESOURCE_VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：按当前 `user_id + id` 更新并追加用户事件。
- **插件 Token**：不允许；插件发现业务不可执行应回传 fail/pause，而不是代表用户跳过。

### `PUT /api/delivery/tasks/:id/greeting`

- **请求参数**：Path `id`；JSON `{ "version", "greeting": "..." }`。
- **响应结构**：`200`，`data: { id, greeting, status, confirmationRequired, version }`。已确认后修改招呼语必须清除旧确认并回到 `PENDING_CONFIRMATION`，执行中和已成功不可修改。
- **权限要求**：Web Session + CSRF。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`GREETING_TOO_LONG`、`INVALID_STATE_TRANSITION`、`RESOURCE_VERSION_CONFLICT`。
- **user_id 隔离**：只更新当前用户任务，并记录“内容变更导致重新确认”的事件。
- **插件 Token**：不允许。

## 9. 插件执行

### `GET /api/plugin/tasks/pending`

- **请求参数**：Query `limit`（默认 10，最大 20）、可选 `platform`；Header 可带 `X-Extension-Version`。
- **响应结构**：`200`，`data: { items: [{ id, version, platform, jobUrl, externalJobId, title, companyName, greeting, confirmedAt, confirmationVersion }], pollAfterSeconds, serverTime }`。只返回执行必要字段。
- **权限要求**：插件 Token，Scope `tasks:read`；设备状态必须 ACTIVE，插件版本兼容。
- **主要错误码**：`PLUGIN_TOKEN_INVALID`、`PLUGIN_TOKEN_EXPIRED`、`DEVICE_REVOKED`、`FORBIDDEN`、`EXTENSION_UPGRADE_REQUIRED`、`RATE_LIMITED`。
- **user_id 隔离**：只查询 Token 用户的 `CONFIRMED` 任务，并匹配未指定设备或当前设备；不返回简历、Cookie 或其他用户任务。
- **插件 Token**：允许，且仅允许插件 Token。

### `POST /api/plugin/tasks/:id/start`

- **请求参数**：Path `id`；JSON `{ "version", "executionId", "extensionVersion", "pageUrl" }`；需 `Idempotency-Key`。`pageUrl` 只保留受支持域名和必要路径，服务端不抓取页面。
- **响应结构**：`200`，`data: { id, status: "EXECUTING", leaseId, leaseExpiresAt, version, task: { platform, jobUrl, greeting } }`。服务器原子完成 `CONFIRMED -> LEASED/EXECUTING` 和短租约。
- **权限要求**：插件 Token，Scopes `tasks:read tasks:write`。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`TASK_ALREADY_CLAIMED`(409)、`INVALID_STATE_TRANSITION`、`RESOURCE_VERSION_CONFLICT`、`DEVICE_REVOKED`、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：Token 的 `user_id` 和设备必须匹配任务归属/分配；领取条件在单条原子 SQL 中完成。
- **插件 Token**：允许，且仅允许插件 Token。

### `POST /api/plugin/tasks/:id/success`

- **请求参数**：Path `id`；JSON `{ "leaseId", "executionId", "version", "completedAt", "resultCode": "DELIVERED", "evidence": { "pageState": "SUCCESS_NOTICE" } }`；需 `Idempotency-Key`。证据只允许枚举/短文本，不上传截图或页面全文。
- **响应结构**：`200`，`data: { id, status: "SUCCEEDED", finishedAt, version }`。重复的同一成功事件返回相同结果。
- **权限要求**：插件 Token，Scope `tasks:write`。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`LEASE_INVALID`(409)、`LEASE_EXPIRED`(409)、`INVALID_STATE_TRANSITION`、`RESOURCE_VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：同时校验 Token 用户、设备、`leaseId` 和执行 ID；成功终态受保护，不被后续失败覆盖。
- **插件 Token**：允许，且仅允许插件 Token。

### `POST /api/plugin/tasks/:id/fail`

- **请求参数**：Path `id`；JSON `{ "leaseId", "executionId", "version", "failedAt", "errorCode": "BUTTON_NOT_FOUND", "message": "未找到可用投递按钮", "retryable": false }`；需 `Idempotency-Key`。允许错误码白名单和短消息。
- **响应结构**：`200`，`data: { id, status: "FAILED", errorCode, retryable, attemptCount, finishedAt, version }`。
- **权限要求**：插件 Token，Scope `tasks:write`。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`LEASE_INVALID`、`LEASE_EXPIRED`、`INVALID_STATE_TRANSITION`、`UNSUPPORTED_ERROR_CODE`(422)、`IDEMPOTENCY_CONFLICT`。
- **user_id 隔离**：校验 Token 用户、设备、租约和执行 ID，写脱敏事件。验证码、登录失效和风控不得用 fail，应调用 pause。
- **插件 Token**：允许，且仅允许插件 Token。

### `POST /api/plugin/tasks/:id/pause`

- **请求参数**：Path `id`；JSON `{ "leaseId", "executionId", "version", "pausedAt", "reason": "CAPTCHA_REQUIRED", "message": "页面要求人工验证" }`；Reason 仅允许 `CAPTCHA_REQUIRED`、`LOGIN_REQUIRED`、`RISK_CONTROL`、`PAGE_CHANGED`、`USER_ACTION_REQUIRED`。
- **响应结构**：`200`，`data: { id, status: "PAUSED", pauseReason, userActionRequired: true, leaseReleased: true, version }`。
- **权限要求**：插件 Token，Scope `tasks:write`。
- **主要错误码**：`RESOURCE_NOT_FOUND`、`LEASE_INVALID`、`INVALID_STATE_TRANSITION`、`UNSUPPORTED_PAUSE_REASON`(422)、`RESOURCE_VERSION_CONFLICT`。
- **user_id 隔离**：只暂停 Token 用户且由当前设备领取的任务；释放租约，Web 明确提示用户处理并重新确认。
- **插件 Token**：允许，且仅允许插件 Token。

## 10. 权限矩阵

| 接口组 | 匿名 | Web Session | 插件 Token |
| --- | --- | --- | --- |
| 注册、登录 | 允许 | 可重新登录 | 禁止 |
| 退出、`/api/me` | 禁止 | 允许 | 禁止 |
| 简历、求职目标 | 禁止 | 允许 | 禁止 |
| 生成绑定码 | 禁止 | 允许 | 禁止 |
| 使用绑定码绑定 | 仅一次性绑定码 | 不作为授权方式 | 绑定前无 Token |
| 插件身份、岗位采集 | 禁止 | 禁止 | 允许且检查 Scope |
| Web 岗位池、AI、投递确认 | 禁止 | 允许 | 禁止 |
| 插件待执行任务和回传 | 禁止 | 禁止 | 允许且检查 Scope/设备/租约 |

## 11. user_id 隔离测试要求

每个资源接口至少要有以下自动化用例：

1. A 用户创建资源，B 用户用同一 ID 执行 GET/PUT/DELETE，统一得到 `404 RESOURCE_NOT_FOUND`。
2. B 用户不能通过批量 ID、排序字段、过滤器或错误消息推断 A 用户资源存在。
3. A 用户插件 Token 不能采集到 B 用户岗位池，也不能领取、开始或回传 B 用户任务。
4. 已撤销设备的旧 Token 对所有插件接口立即失败。
5. 修改已确认任务内容会清除旧确认；没有 `confirmed_at` 的任务永远不会出现在插件 pending 列表。
6. 两台设备并发 start 同一任务，只有一台获得租约；重复 success 幂等且终态不倒退。
7. 日志和错误响应中不出现密码、Cookie、Token、简历全文和跨用户数据。

## 12. 第一版明确不提供的 API

- 招聘平台账号密码、Cookie、登录态上传或读取接口。
- 服务端登录招聘平台、批量代投、验证码识别/绕过接口。
- 允许插件直接修改简历、求职目标、额度或确认投递的接口。
- 无用户确认的 `auto-deliver` 接口。
- 微信登录、复杂订阅、优惠券、分账和多支付渠道接口。
