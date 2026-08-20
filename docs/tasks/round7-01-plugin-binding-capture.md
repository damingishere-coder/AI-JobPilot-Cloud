# 第七轮任务 1（P7）：插件绑定码、设备心跳与岗位采集上传

## 范围与安全边界

本任务把一次性绑定码从 Redis 迁到 PostgreSQL（数据库只存 SHA-256 哈希，唯一事实源），新增插件心跳与岗位采集上传端点（`/api/plugin/jobs/capture`、`/batch-capture`）、Web `/plugin` 设备管理页，并让 Chrome 扩展（MV3）支持绑定、心跳与岗位上传。Redis 只用于限流（IP/失败尝试）和绑定码的短时幂等回放缓存。

- 不开启自动投递执行（`delivery.execution-enabled` 保持默认 false）。
- 不绕过验证码、不读取招聘平台账号密码、Cookie 或其他页面脚本；采集只保留白名单字段。
- 不修改 V1-V8 数据库迁移；本任务新增 V9。
- 不执行 git push / 部署 / 生产数据库迁移。

## 绑定步骤（用户视角）

1. 登录 Web 后进入「浏览器插件」页，点击「生成一次性绑定码」。绑定码默认 5 分钟过期，数据库只保存不可逆哈希；同一 Idempotency-Key 重试返回同一个码。
2. 打开 Chrome 扩展「投递牛马 Cloud Bridge」弹窗，选择预设本地 Cloud API 地址或输入自定义地址（远程仅 `https://` 合法 Origin，本地仅 `localhost/127.0.0.1` + 明确端口），输入绑定码与设备显示名，点击绑定。首次绑定远程 https 地址时，Chrome 会按需请求该精确 `${origin}/*` 的主机权限，用户拒绝则本次绑定失败且不保存任何 Token（fail-closed）。
3. 绑定成功会一次性返回插件 Token（默认 90 天有效，scope：`device:read`、`tasks:read`、`tasks:write`、`jobs:write`）。Token 只保存在扩展的 `chrome.storage.local`，绝不进 `chrome.storage.sync`、DOM、URL、日志或剪贴板。
4. 每台设备绑定后即可接收投递唤醒、上报心跳、上传采集岗位。Web 端可随时撤销设备，撤销后其 Token 立即失效（心跳与上传均被拒绝）。

## 岗位上传步骤（用户视角）

1. 在 Chrome 中打开 BOSS 直聘或智联招聘的岗位详情页（仅限真实平台 https 页面，扩展 manifest 没有 `<all_urls>`）。
2. 点击扩展弹窗「上传当前岗位」：后台脚本只转发固定白名单字段（platform、platformJobId、jobUrl、title、salary、city、district、companyName、companySize、industry、experience、education、benefits、jobDescription、hrName、capturedAt），URL 先剥离 query/fragment，securityId/lid/encryptBossId/Cookie/账号密码/HTML 一律不会离开扩展；服务端再做一次相同规则的清洗后写入云端岗位池。
3. 云端不可用时（网络/429/5xx）岗位自动暂存到本地采集队列（去重、上限 200），可点「上传已采集岗位」逐条重试上传（线性退避，最多 3 次）。服务端返回 created/duplicate 的条目移出队列，校验失败的条目保留并记录重试次数供用户修正；已成功上传的岗位绝不重复上传。

## 云端岗位池写入

采集岗位直接写入 `app.job_posts`（V4 岗位池，与 Web `/api/jobs`、AI 匹配、投递流程共用一行）：

- `external_job_id = platformJobId`；`platform` 归一化为 `BOSS` / `ZHILIAN`（其它值 400）。
- `fingerprint` 为服务端 SHA-256（规范化 `platform:platformJobId`），绝不采用客户端提交值。
- `location` 由 city/district 稳定拼接；`company_info` 只保留白名单键 companySize/industry/district/hrName；`welfare` 存 benefits；`skills` 为空数组；**不存储 raw_payload**。
- 幂等去重依赖 V4 唯一索引 `(user_id, platform, external_job_id)`：同一用户重复上传返回同一 `job_posts.id` + `duplicate`，且只刷新 last_seen_at/updated_at，绝不覆盖 status、匹配结果、投递状态或任何人工编辑数据；不同用户各自一行。

## 重新绑定获取 jobs:write

P7 之前签发的历史 Token 没有 `jobs:write` scope：投递任务接口不受影响，但岗位采集上传会返回 403。在 Web 设备管理页撤销旧设备后重新绑定（或直接用新绑定码重新绑定同一设备）即可获得完整 scope。

## API 一览（以源码为准）

Web 会话（CSRF 保护）：

- `POST /api/plugin/bind-code`（Header `Idempotency-Key` 必填）→ `{bindCode, expiresAt, expiresInSeconds}`
- `GET /api/plugin/devices` → 设备列表
- `POST /api/plugin/devices/{id}/revoke`，body `{reason}`

插件 Token（无 CSRF，CORS 仅放行精确扩展 Origin）：

- `POST /api/plugin/bind`（匿名，一次性绑定码）→ 设备 + 一次性明文 Token
- `GET /api/plugin/me`（`device:read`）
- `POST /api/plugin/heartbeat`（`device:read`）→ 强制刷新 last_seen_at，返回 `{deviceId, userId, status, lastSeenAt}`
- `POST /api/plugin/jobs/capture`（`jobs:write`）→ `{id, status: created|duplicate}`；`id` 即 `job_posts.id`
- `POST /api/plugin/jobs/batch-capture`（`jobs:write`）→ `{items: [{id, status: created|duplicate|failed, errorCode?, message?}], created, duplicates, failed, total}`（统计与 items 严格一致）

采集条目字段可同时使用 camelCase 与 snake_case 精确别名（`platform_job_id`、`job_url`、`company_name`、`company_size`、`job_description`、`hr_name`、`captured_at`）；`user_id`/`device_id` 无别名，未知字段一律 400。

限流：采集上传按认证后的 deviceId 维度（Redis 计数），配置在 `application-api.yaml` 的 `app.rate-limit.plugin-job-capture-*`。绑定尝试按 IP 与单个绑定码维度限流。

## Cloud API 地址与权限限制（扩展）

- 预设本地入口：`http://localhost:8080` / `127.0.0.1:8080` / `8888` / `6866` 六个地址；同时允许自定义地址：本地仅 `http://localhost|127.0.0.1` + 明确端口，远程仅 `https://` 合法 Origin（拒绝 userinfo/path/query/fragment，标准端口 443 归一化，远程 http 一律拒绝）。
- 静态 `host_permissions` 仅包含本地入口与 `*.zhipin.com` / `*.zhaopin.com`，无 `<all_urls>`、无 `https://*/*`；动态/可选模式只声明在 `optional_host_permissions`（`https://*/*` + `http://localhost/*` + `http://127.0.0.1/*`）。绑定远程 origin 时通过 `chrome.permissions.request` 请求精确 `${origin}/*`，用户拒绝则绑定失败（fail-closed，不保存 Token）。权限不包含 cookies/webRequest 等敏感能力。
- 每次 API 调用与读取绑定状态时都会重新校验并规范化 apiBase；Token 只进 `chrome.storage.local` 与请求 Authorization Header。
- 服务器 CORS 只允许精确的 `chrome-extension://<32位a-p扩展ID>` Origin（开发默认值由 manifest 固定公钥派生），不支持通配符。

## 数据库（V9）

- `app.plugin_bind_codes`：只存绑定码 SHA-256 哈希；状态 `ACTIVE/CONSUMED/EXPIRED/SUPERSEDED`；每用户 active 上限（默认 3，超出自动废弃最旧）；创建与一次性消费走 SECURITY DEFINER 函数，消费与设备/Token 签发同事务。
- `app.plugin_devices.device_type`：新增可选列；`app.plugin_tokens` scope CHECK 增加 `jobs:write`；审计新增 `PLUGIN_JOB_CAPTURED`。
- 不新增采集表：采集岗位写入 V4 `app.job_posts`（见上文映射与幂等规则）。

## 验证

- 后端：`./gradlew test`（含 `PluginDeliveryIntegrationTest` 的 P7 场景：绑定码哈希与过期、心跳 last_seen、job_posts 写入与去重、snake_case 别名与越权字段 400、Web `/api/jobs` 可见性、批次统计一致性、jobs:write 403、无旧采集表）。
- 前端：`cd front && pnpm lint && pnpm build`。
- 扩展：`node --check` 全部改动的 JS；`manifest.json` 可解析且无 `<all_urls>`（静态 host_permissions 无 `https://*/*`）；`node --test tests/*.test.cjs` 全部通过。

> 本文档不包含任何真实 token/key/cookie。
