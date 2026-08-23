# 第五轮任务 2B：Chrome 扩展绑定与 Cloud 投递闭环

## 执行角色与现有基线

你是 Claude Code CLI + DeepSeek 受限执行者。Codex 已完成需求、架构、安全边界和验收设计；只按本任务实现、测试和报告，不重新决定产品语义。

- 项目：`C:\Users\10578\Documents\AI-JobPilot-Cloud`
- 分支：`feature/ai-matching-delivery`
- 基线提交：`63052db 新增：实现投递任务与插件鉴权`
- 开始前确认 `git status` 干净并阅读根目录 `AGENTS.md`、`CLOUD_ARCHITECTURE.md`、`CLOUD_SECURITY.md`、`CLOUD_API_DESIGN.md`。
- 已有后端：一次性绑定码、插件 Token、设备管理、pending/start/success/fail/pause、租约、幂等和审计均已通过 213 项测试。
- 已有扩展：Boss/智联的真实页面导航、按钮点击和 Boss 招呼语能力可复用；当前仍使用旧 localhost 投递结果接口，本任务将为 Cloud 执行路径接入新 API。
- 不执行 Git add/commit/push/PR，不发布扩展、不部署、不访问真实招聘平台、不使用真实账号/Token/绑定码。

## 已批准的产品与架构语义

1. 只有 Web 用户逐条确认后，网页才显式发送一次 `CLOUD_DELIVERY_WAKE`。扩展不使用 timer/alarm/background poll，不自动扫描 pending，不做批量 Cloud 投递。
2. 网页桥只传 `taskId + requestId` 等最小稳定字段；插件 Token、设备信息、岗位 URL、招呼语不得进入页面消息或 DOM。
3. 扩展后台从 Cloud pending 精确找到该 taskId，再调用 start；实际导航 URL 和 Boss greeting 只采用 start 响应，不能信任网页消息。
4. 同一时刻只执行一个 Cloud 投递任务。相同 taskId 的重复显式唤醒恢复同一执行；不同 taskId 在执行中返回 `PLUGIN_BUSY`。
5. 扩展离线/未绑定/Token 失效时，后端任务保持 `CONFIRMED`。扩展只报告稳定状态，不能替用户 skip/confirm，也不能静默重试或轮询；用户在 Web 点击“重试唤醒”才恢复。
6. Boss 可发送服务器已确认的 greeting；智联不接收/发送自定义 greeting。
7. 验证码、滑块、人机校验、登录失效、风控、页面结构/目标改变或必须人工选择时立即停止，不自动解决、不继续点击，调用 pause。岗位关闭等确定业务终态用 fail；明确投递/已投递用 success。
8. Cloud 执行路径只回传固定枚举、短固定摘要和 evidence 白名单；不上传 Cookie、LocalStorage、SessionStorage、页面全文、HTML、截图、选择器、浏览器历史或完整 URL query。
9. 本批支持仓库当前本地 Cloud 开发入口：`http://localhost|127.0.0.1` 的 `8080`（Docker 网关）、`8888`（本机 API）和 `6866`（Next 开发代理）。生产域名和商店发布属于部署阶段，不能虚构。

## 1. 稳定开发扩展 ID 与精确 Cloud CORS

### 1.1 固定公开开发 ID

- 为 `chrome-extension/manifest.json` 生成并写入稳定的 RSA **公钥** `key`，将版本从 `1.3.0` 升为 `1.4.0`；公钥不是秘密，可以提交。
- 生成过程只可在内存或系统临时目录处理私钥；仓库中严禁出现私钥、PEM、证书或随机密钥文件。最终报告写出由公钥派生的 32 字符开发扩展 ID。
- `manifest` 新增最小 `action.default_popup`，并包含 `popup.html/js/css`；不得新增 `<all_urls>`、cookies、history、webRequest、clipboard、alarms 等权限。
- `host_permissions`/Web bridge matches 补当前本地 Cloud `8080`；保留确有用途的 6866/8888、Boss、智联权限。

### 1.2 Cloud API 精确 CORS

- 在 `com.getjobs.cloud` 范围实现 Cloud 专用 CORS；旧 `com.getjobs.application.config.CorsConfig` 不在 Cloud 扫描范围，不得把它的 `chrome-extension://*` 当成 Cloud 方案。
- `PluginProperties` 新增可配置的 `allowedExtensionOrigins`，开发默认值必须精确等于上面公钥派生的 `chrome-extension://<id>`；非空项只接受严格 `chrome-extension://[a-p]{32}`，不得使用 wildcard/pattern。
- 只为 `/api/plugin/bind`、`/api/plugin/me`、`/api/plugin/tasks/**` 开放这些精确 Origin，方法仅 `GET/POST/OPTIONS`，请求头仅实际需要的 `Authorization, Content-Type, Idempotency-Key`；插件 API 不使用 Cookie，`allowCredentials=false`。
- Plugin Spring Security chain 显式启用该 CORS source，预检不能被 Token filter 当成 401。
- `.env.example` 和 `docker-compose.yml` 透传 `PLUGIN_ALLOWED_EXTENSION_ORIGINS`，只含公开 origin，不含秘密。
- 测试：正确开发 ID 的 bind、Bearer、Idempotency-Key 预检通过；其他扩展 ID、普通网站、通配 origin 被拒；实际 GET/POST 仍需原有绑定码或 Token，不能只信 Origin。

## 2. 扩展弹窗绑定与本地凭证边界

新增简洁中文 popup：

- API 地址只能从本任务批准的本地 origin 下拉选择，默认 `http://localhost:8080`；不得允许任意 URL、路径、userinfo、query 或 fragment。
- 输入 Web 生成的绑定码和设备显示名，点击后调用 `POST /api/plugin/bind`；安装 ID 用 Web Crypto 生成至少 256-bit 随机值，只存 `chrome.storage.local`，不是硬件指纹。
- `browserName/browserVersion` 从扩展环境的非敏感浏览器信息得到，`extensionVersion` 必须使用 `chrome.runtime.getManifest().version`，capabilities 固定 `BOSS,ZHILIAN`，请求不得包含 userId。
- 绑定成功后把 API base、installationId、Token、Token expiresAt、最小设备摘要存入 `chrome.storage.local`。不得使用 `chrome.storage.sync`、网页 localStorage/SessionStorage、剪贴板、URL、DOM、console 或异常文本保存/输出 Token。
- popup 打开时有 Token 才调用 `GET /api/plugin/me` 显示“已连接/设备/有效期”；401/403 的 `PLUGIN_TOKEN_INVALID|PLUGIN_TOKEN_EXPIRED|DEVICE_REVOKED|ACCOUNT_DISABLED` 清理本地 Token 并提示重新绑定。
- “解除本机绑定”只清除本机 Token 和执行状态，并明确提示用户若要服务端撤销需在 Web 设备管理中操作；不得伪造已撤销成功。
- 绑定响应必须检查 `response.ok` 和统一 API envelope；不能把完整响应、请求 header 或 Token 拼入错误。

建议把 Cloud API、存储和执行协议拆到可测试的 `cloud-client.js`，background/popup 共享固定常量，避免在巨大 `background.js` 重复安全逻辑。

## 3. 最小网页桥与显式唤醒

- `page-bridge.js` 和 `background.js` 只新增 `CLOUD_DELIVERY_WAKE` 页面请求；必须校验来源是允许的 6866/8080 精确页面 Origin、`event.source===window`、`source/type`、UUID taskId 和有界 requestId。
- background 回应只含 `success/accepted/code/message/taskId/state` 等稳定字段，不返回 Token、岗位 URL、greeting、lease、executionId、设备或 API 原始响应。
- 接到合法唤醒后立即返回“已接收”或明确 `PLUGIN_NOT_BOUND/PLUGIN_BUSY/VALIDATION_ERROR`；耗时执行异步进行。
- 执行进度通过现有 `GET_JOBS_EXTENSION_EVENT` 回到最初 page tab，payload 只含 taskId、稳定 stage/code/message/time。至少包含 `accepted/fetching/starting/navigating/executing/reporting/succeeded/failed/paused/offline`。
- 不得加入 `setInterval`、`setTimeout` 循环、`chrome.alarms` 或 `onStartup` 自动领取。允许在单次显式执行内部等待页面加载；浏览器/Service Worker 重启后也必须等用户再次显式唤醒才恢复。

## 4. 可恢复的单任务 Cloud 状态机

### 4.1 领取与持久化

每个 Cloud task 使用稳定随机执行元数据，保存在 `chrome.storage.local`，避免 MV3 Service Worker 中断造成重复领取：

- 在 start 网络请求**之前**持久化 `taskId, executionId, startIdempotencyKey, pendingVersion, phase, updatedAt`；executionId 至少 128-bit 随机且符合后端格式。
- 重复唤醒同 taskId 必须复用相同 start 请求；即使前次响应丢失，也用原 key/payload调用 start 幂等重放。
- start 成功后持久化 `leaseId, version, attemptNumber` 和服务器返回的最小执行 payload，再导航；不得持久化 Token 的副本。
- content 结果产生后，在 finish 请求前先持久化已规范化的 `reportKind + reportPayload + reportIdempotencyKey`；网络失败时保留，下一次显式唤醒原样重放。
- success/fail/pause 成功后清理活动执行状态，仅保留不敏感的最近结果（如有，应有数量/时限上限）。
- 另一个 task 唤醒时若已有新鲜活动执行，返回 `PLUGIN_BUSY`；陈旧状态只能按明确、保守且测试覆盖的租约/时间规则处理，不能并行开始第二个任务。

### 4.2 Cloud API 调用

- 显式唤醒且无可恢复状态时调用 `GET /api/plugin/tasks/pending?limit=20`，只选择完全等于 taskId 的条目；未找到返回 `TASK_NOT_AVAILABLE`，不改服务端任务。
- 调用 `POST /api/plugin/tasks/{id}/start`，只发送后端契约字段和稳定 Idempotency-Key；使用 manifest 数字版本，不发送网页 URL。
- 所有绑定后请求从 `chrome.storage.local` 读取 Token 并设置 Bearer，Token 只存在请求 Header 内存中；不得发给 content script。
- 401/403 按稳定错误码清 Token/停止；409 按错误码区分幂等冲突、版本变化、租约问题，不能无界自动重试；429/5xx/网络错误报告 offline/retryable 并等用户再次显式唤醒。

## 5. 复用 Boss/智联真实页面执行，但隔离旧结果接口

- background 只用 start 响应的 `platform/jobUrl/greeting` 构建 `cloudManaged` content task；Token、lease、executionId 不传入招聘平台 content script。
- 使用现有标签页查找、受信 URL、导航、content ready 和 `BOSS_DELIVER_CURRENT_V2` / 智联 current 消息能力；Cloud 仍必须再次严格校验 HTTPS + 正确 host label + 岗位详情 path，拒绝 lookalike、首页、搜索页、端口、userinfo、query/fragment 和编码绕过。
- Cloud `cloudManaged` 路径不得调用旧 `/api/boss/jobs/*/delivery-result`、`/api/zhilian/jobs/*/delivery-result` 或其他 localhost 结果接口；content script只把受控结果返回 background。保留旧本地扫描/投递路径兼容，不做无关重构。
- Boss：若页面已明确“继续沟通/已沟通”，返回 `ALREADY_DELIVERED`，不再次发送 greeting；新沟通成功后再发送已确认 greeting。禁止把 greeting 写日志/进度/证据。
- 智联：不得使用 greeting。只有检测到明确的“已投递/申请成功”等状态才 success；Cloud 路径若点击后无法确认结果，pause `USER_ACTION_REQUIRED`，不能乐观上报成功。
- content 返回只允许：`success/resultCode/pageState/failureType` 和短固定 message；不返回 DOM/HTML/页面全文/URL/选择器/截图。

## 6. 结果映射与安全暂停

background 将 content 结果规范为后端契约：

- 明确新投递成功：`success`, `resultCode=DELIVERED`, evidence `{pageState:"SUCCESS_NOTICE"}`。
- 明确已经投递/沟通：`success`, `resultCode=ALREADY_DELIVERED`, evidence `{pageState:"ALREADY_DELIVERED",alreadyDelivered:true}`。
- 登录失效：`pause LOGIN_REQUIRED`。
- 验证码/滑块/人机校验：`pause CAPTCHA_REQUIRED`。
- 风控/账号异常/操作频繁：`pause RISK_CONTROL`。
- 目标页面或 DOM 结构与预期不符：`pause PAGE_CHANGED`。
- 必须用户选择简历或无法确认是否成功：`pause USER_ACTION_REQUIRED`。
- 岗位关闭：`fail JOB_CLOSED`；按钮确定不存在：`fail BUTTON_NOT_FOUND`；扩展网络型执行错误：`fail NETWORK_ERROR`；其余固定安全摘要：`fail UNKNOWN_ERROR`。

`message` 必须从本地固定映射表产生并满足后端长度/单行/敏感词限制，不能直接转发 DOM 文本或异常堆栈。`completedAt/failedAt/pausedAt` 使用当前 ISO 时间；retryable 由服务端决定，客户端值不作为依据。

## 7. 必须测试

在现有 Node `node:test` + VM/mock Chrome 风格下补自动化测试，不能依赖真实浏览器或网络。至少覆盖：

1. manifest 是 MV3、版本 1.4.0、固定公钥派生 ID 与后端默认 exact Origin 一致；权限没有 wildcard/cookies/history/alarms/clipboard，popup 与所有脚本存在。
2. popup 绑定请求字段正确；installationId/Token 只进 `chrome.storage.local`；失败不落 Token；`me` 失效清 Token；不允许任意 API origin。
3. page bridge 拒绝错误 origin/source/type、非 UUID taskId；合法 wake 不包含业务详情或凭证。
4. 未绑定返回 `PLUGIN_NOT_BOUND`；显式 wake 才调用 pending，空闲时没有 timer/alarm/pending 网络请求。
5. exact taskId 才 start；start URL/greeting 只来自服务器响应；智联 greeting 始终丢弃；恶意 job URL 在导航前拒绝。
6. start 请求前已持久化稳定 execution/key；模拟“请求成功但响应丢失 + Service Worker 重启 + 再次 wake”仍重放同一 start；finish 同理。
7. 同 task 去重恢复、不同 task 返回 busy；不能调用 Cloud batch。
8. Boss/Zhilian success/already/fail/pause 全部映射；CAPTCHA/LOGIN/RISK/PAGE_CHANGED/USER_ACTION_REQUIRED 不继续点击、不误用 fail。
9. `cloudManaged` content 路径不调用旧 delivery-result；Boss 已沟通不重复 greeting；智联无明确成功状态时不乐观成功。
10. 任何页面/进度/content 消息、错误、console spy、storage sync 中都没有测试 Token sentinel、Authorization、Cookie、greeting sentinel、lease/executionId。
11. Cloud CORS 精确 origin 与预检测试；原 213 项 Java 测试和原 54 项扩展测试继续通过。

## 8. 验收命令

必须执行并报告真实数量：

```powershell
node --test chrome-extension/tests/*.test.cjs
node scripts/validate-chrome-extension.mjs
.\gradlew.bat clean test --console=plain
docker compose config --quiet
git diff --check
node scripts/validate-repository-hygiene.mjs
```

额外静态确认：

- Cloud 路径没有 `setInterval`、`chrome.alarms`、`storage.sync`、批量 Cloud 类型。
- `rg` 检查 Token/Cookie/LocalStorage/SessionStorage 仅出现在安全拒绝或注释，不能形成上传/消息/日志通道。
- 仓库没有私钥、真实 Token/绑定码、测试浏览器缓存或生成证书。

## 9. 非目标

- 不实现真实生产 API 域名、Chrome Web Store 发布、自动安装/更新、签名包或部署。
- 不实现 Cloud 岗位采集上传（`jobs:capture`），本批只做绑定和确认后投递。
- 不实现后台静默轮询、WebSocket/SSE push、批量确认/投递、自动确认、验证码绕过、重放平台 Cookie 或服务器 DOM。
- 不修改 V1-V6 数据库迁移；如 CORS 只需配置/Java，不新增迁移。
- 不大改旧本地扫描逻辑；只加清晰的 Cloud 执行分支和最小共享抽取。

最终报告必须列出：派生开发扩展 ID、文件、状态机、CORS、测试数量/失败/跳过、每条验收证据、未完成项与剩余风险。不得只写“测试通过”。
