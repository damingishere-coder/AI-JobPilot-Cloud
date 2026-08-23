# AI-JobPilot-Cloud 隐私与安全上线检查清单

本文是云端服务上线前的隐私与安全核对清单。**只记录承诺与已实现的能力，不虚构未实现的功能。** 未通过项上线前必须完成或在发布说明中显式披露风险。

## 1. 法律与用户授权

- [ ] 提供服务条款与隐私政策，注册时强制勾选同意（`acceptTerms` 校验已实现，版本记录在审计事件 `AUTH_REGISTER` 的 `termsVersion` 字段）。
- [ ] 隐私政策明确收集的数据类型、用途、保存期限与第三方共享范围。
- [ ] 用户删除机制：账号删除、简历删除（软删除 `RESUME_DELETE_REQUESTED` + 定期清除 `RESUME_PURGED`）与存储对象清除流程已定义并向用户说明。
- [ ] **未实现项**：账号级彻底删除（GDPR 擦除）目前仅有设计预留（`users.deleted_at`），尚无自助删除入口；上线前需补齐或披露。
- [ ] 未成年人与敏感行业（医疗、金融简历）的特殊条款按地区法规评估。

## 2. 数据收集最小化

- [ ] 注册只收集邮箱与密码；简历文件由用户主动上传。
- [ ] **承诺禁止**：不采集招聘平台网站 Cookie（插件侧 `chrome.storage.local` 只存插件 Token，见 `chrome-extension/` 实现与 `CLOUD_SECURITY.md` 第 5 节）。
- [ ] Cloud 外层和内层 Nginx 明确阻断旧本地版页面、`/api/cookie/**` 及对应本地平台 API；旧源码不得作为 Cloud 公网功能入口，生产抽查必须确认这些路径返回 404。
- [ ] 不采集浏览器指纹、设备硬件指纹：插件只保存随机 `installation_id` 的 HMAC 哈希。
- [ ] 岗位采集上传端点（`POST /api/plugin/jobs/capture`、`/batch-capture`，Scope `jobs:write`）已实现，待运行时验收：按认证后的 `deviceId`（服务端从 Token 哈希解析）做 Redis 限流，绝不使用原始 Token 作为 key；每次采集上传成功写审计事件；采集字段只允许白名单结构。
- [ ] 日志不记录请求体、简历全文、岗位全文；AI 匹配日志只记录 matchId/jobId/traceId 与安全化的类型/错误码。
- [ ] 审计 `detail_json` 只包含白名单字段，且经 `SensitiveDataSanitizer` 二次脱敏（禁止密码、Token、API Key、Cookie、邮箱、手机号、简历/岗位全文）。
- [ ] IP 与 User-Agent 只以 HMAC 哈希（`ip_hash`）和浏览器/系统摘要（`user_agent_summary`）写入审计表，不存明文。

## 3. 账号与认证安全

- [ ] 密码使用 Argon2id 单向哈希 + 独立 Salt；可选 Pepper（HMAC）注入自 `.secrets/`，不落库、不入仓库。
- [ ] 登录失败统一提示“账号或密码错误”，不暴露邮箱是否注册；连续失败触发临时锁定（`AUTH_ACCOUNT_LOCKED`）。
- [ ] 登录/注册/CSRF 按 IP+邮箱哈希维度 Redis 限流；AI 匹配与简历上传按 userId 限流，统一 429 `RATE_LIMITED` + `Retry-After`。
- [ ] 插件待办轮询（`GET /api/plugin/tasks/pending`）按认证后的 deviceId 维度 Redis 限流（默认 60 次/分钟，约为 `poll-after-seconds=10` 正常节奏的 10 倍余量），键中不含原始 Token。
- [ ] Web Session 存 Redis，登录成功轮换 Session ID；退出立即使服务端会话失效；插件设备撤销同步撤销其 Web 侧影响范围。
- [ ] CSRF 防护覆盖 Web 全部写操作（`X-CSRF-TOKEN`）；插件 API 无 Cookie、无 CSRF，靠受限 Scope Token。
- [ ] 会话 Cookie：`HttpOnly` + `SameSite=Lax` + 固定 Path；`AUTH_COOKIE_SECURE` 生产必须为 `true`（公网部署要求 HTTPS 前置）。
- [ ] 插件 Token：CSPRNG 生成、明文只返回一次、数据库只存 SHA-256 哈希、常量时间比较、Scope 最小化、可撤销、有有效期。

## 4. 传输与基础设施安全

- [ ] 公网入口强制 HTTPS；Nginx/API/存储/AI 服务连接一律 TLS；PostgreSQL/Redis 不映射公网端口（默认只暴露 `127.0.0.1:8080`）。
- [ ] CORS 无通配符：云端 API 进程（`api` profile）的 Web 端点与前端同源部署（Nginx 同源），Web 安全链不启用任何 CORS，对任何 Origin 都不返回 CORS 头（默认拒绝，浏览器侧阻断跨源）；插件端点仅精确 `chrome-extension://<32位a-p ID>`（`PLUGIN_ALLOWED_EXTENSION_ORIGINS`，`api` profile），通配符配置会在启动时失败。本地桌面应用（`application` profile）的 `APP_ALLOWED_ORIGINS` / `APP_ALLOWED_EXTENSION_ORIGINS` 仅作用于其自身进程，云端进程不加载。
- [ ] 密钥管理：所有密钥来自 `.secrets/`（Docker Secret / KMS），环境变量示例文件只含占位符；本项目使用 Redis Session，不使用 `JWT_SECRET`；插件 Token 使用高熵随机值 + SHA-256 哈希，不需要 `PLUGIN_TOKEN_SECRET`；禁止硬编码数据库密码、Redis 密码、AI API Key、腾讯云 SecretId/SecretKey。
- [ ] 数据加密 Key 与备份介质分离存放；Key 轮换与旧 Key 解密窗口有明确方案（`DataEncryptionService` 的 key-id 设计已预留）。
- [ ] 简历上传经过 ClamAV 恶意文件扫描；文件大小/类型白名单校验。
- [ ] 容器非 root、最小镜像；依赖漏洞扫描（CodeQL、dependency-review 已接入 CI）。

## 5. 数据隔离与最小权限

- [ ] 所有用户业务表按 `user_id` 隔离：服务层 `user_id + resource_id` 双重过滤 + PostgreSQL `ENABLE ROW LEVEL SECURITY`；运行角色必须是非表 owner、非超级用户且无 `BYPASSRLS`。迁移 owner 只用于迁移和受审阅的 `SECURITY DEFINER` 函数，不作为应用连接角色。
- [ ] 客户端传入的 `userId` 参数被 `UserIdParameterFilter` 直接拒绝（400）。
- [ ] 越权访问统一 404/403 响应结构，不泄露资源存在性与内部细节（覆盖简历、岗位、匹配、投递任务；集成测试 `TenantIsolationIntegrationTest` 全链路验证）。
- [ ] 数据库运行角色 `jobpilot_app` 无 DDL 权限、无法直接读写 `audit_logs`（仅 SECURITY DEFINER 函数写入）；迁移使用独立 owner 角色。
- [ ] 插件任务状态只能经窄 SECURITY DEFINER 函数变更；投递任务事件表对应用角色只增不改。
- [ ] 管理员后台当前仅允许 ACTIVE ADMIN 会话；没有通过环境变量密码自动提权，不默认返回跨用户简历全文，跨用户管理操作使用最小字段并写审计。

## 6. 日志与异常边界

- [ ] 全局异常处理只返回稳定错误码与通用消息；堆栈、内部异常消息、SQL 细节只进服务端日志且经脱敏。
- [ ] 基础设施异常文本经 `SensitiveDataSanitizer` 统一清理（Bearer/JWT、插件 Token、密码/Secret 键值对、Set-Cookie、数据库 URL 内嵌密码、SecretId/SecretKey、邮箱、手机号）。
- [ ] 访问日志不记录 `Authorization` 头与 Cookie 值。
- [ ] 审计事件白名单（Java `AuditWriter.ALLOWED_ACTIONS` 与数据库 CHECK 约束）保持一致；未知事件无法写入。

## 7. 审计与可追溯

- [ ] 关键动作全部审计：注册、登录成功/失败/锁定、上传/删除简历、修改求职目标、AI 匹配请求/成功/失败/复用、投递任务创建/确认/跳过/招呼语修改、插件绑定/撤销、插件任务执行/成功/失败/暂停、插件实际拉取到待办任务（`PLUGIN_TASKS_PULLED`，actor 为插件设备；空轮询不写审计，避免日志膨胀）。
- [ ] 审计行携带 `ip_hash`、`user_agent_summary`、`request_id` 与白名单 `details`；不存在明文 IP/UA/凭证。`PLUGIN_TASKS_PULLED` 的 `details` 只含 `count`/`limit`/规范化 `platform`，不含 Token、URL、岗位标题或招呼语。
- [ ] 管理员审计日志查询后台（`GET /api/admin/audit-logs`）已实现，待运行时验收；仅允许 ACTIVE ADMIN 会话访问，返回脱敏审计字段，不包含 `details`、IP、User-Agent、凭证或简历/岗位全文。

## 8. 隐私承诺与文档一致性

- [ ] 隐私政策中关于“删除后保留期限”的承诺与 `resumes.deleted_at`/清除任务实际保留期一致。
- [ ] 用户协议中的限流/锁定策略与实际阈值一致（阈值可通过环境变量调整时，文档标注默认值）。
- [ ] 备份保留策略与隐私删除义务一致（见 `CLOUD_BACKUP_RECOVERY.md` 第 10 节）。
- [ ] 上线公告与帮助文档不得暗示“管理员可见所有数据”等与隔离模型冲突的能力。

## 9. 发布前必测项

- [ ] `./gradlew test` 与 `./gradlew build` 全绿（含 Testcontainers 的 RLS/跨租户/投递/插件集成测试）。
- [ ] `cd front && pnpm lint && pnpm build` 通过。
- [ ] 预发布环境以生产 Profile + 真实 `.secrets/` 冒烟：注册 → 上传简历 → 设置目标 → AI 匹配 → 投递任务 → 插件绑定执行全链路。
- [ ] 预发布环境验证 `AUTH_COOKIE_SECURE=true` 下 HTTPS 正常、HTTP 拒绝。
- [ ] 备份恢复演练一次并记录结果（`CLOUD_BACKUP_RECOVERY.md` 第 5 节）。

## 10. P10 部署契约与上线阻塞项

- [ ] 代码静态检查与腾讯云人工检查分开记录；仅有测试通过不能替代公网 HTTPS、安全组、证书、备份恢复和隐私验收。
- [ ] Compose 生产只把 Nginx 绑定到宿主机 `127.0.0.1:8080`；公网安全组只允许 80/443，22 仅固定管理 IP；5432/6379/8888/8889/6866/8080 不得公网开放。
- [ ] `AUTH_COOKIE_SECURE=false` 仅用于回环地址本地 HTTP；腾讯云生产必须为 `true`，HTTPS 登录/CSRF/退出通过后才可放真实用户。
- [ ] `APP_STORAGE_LOCAL_ROOT` 在 local 模式实际消费为容器私有卷 `/var/lib/ai-jobpilot/private`；S3 模式使用私有 Bucket 版本化和 KMS。当前代码没有消费 `APP_PUBLIC_URL`，公网地址由宿主机 Nginx 的域名/Host/HTTPS 提供，不得把未使用变量当作安全控制。
- [ ] 生产 Profile 日志优先 stdout/stderr 和 Docker 日志轮转；本地旧版的 `APP_LOG_DIR` 不作为云端公开日志目录。
- [ ] PostgreSQL、上传文件和数据加密 Key 均有独立备份；Redis AOF 已启用但 Redis 不是事实源，Redis 丢失只导致重新登录/重新绑定及队列缓冲重建。
- [ ] 已实现简历删除/清除、尚未实现账号级自助删除、岗位/匹配/任务/额度/插件数据拟删除顺序、审计最小保留/匿名化和备份自然过期规则均已对用户披露。
- [ ] 未实际部署、未实际恢复演练、隐私政策/用户协议未定稿或账号级删除未实现时，结论必须为“不可邀请真实用户”。

## 11. 用户数据删除方案（设计状态）

> 本节是待实现的删除方案和对外披露边界，不代表账号级删除接口已经存在。当前版本只实现简历删除/清除：先记录删除请求，再清除简历原文件和提取文本；账号级自助彻底删除尚未实现，必须继续作为真实用户准入阻塞项。

- [ ] 账户删除请求必须先重新认证，并要求二次确认；服务端必须要求幂等操作键，重复请求只能返回同一结果，不能重复执行或扩大删除范围。
- [ ] 账户删除应设置冷静期和可取消通道；冷静期结束后才进入不可逆清除，取消请求必须同样幂等并写入最小审计记录。
- [ ] 进入删除流程后立即撤销该用户的全部 Web Session、插件 Token 和绑定码，并暂停其未完成任务；不得让删除期间继续拉取、执行或回传任务。
- [ ] 账户级删除范围至少包括：私有简历对象及提取文本、求职偏好、岗位与匹配记录、投递任务及任务事件、额度及订阅预留、插件设备及其绑定关系。实现时必须按依赖顺序删除或匿名化，并验证没有跨用户影响。
- [ ] 审计日志只保留删除所需的最小安全字段（操作类型、不可逆请求标识、结果码、时间和去标识化主体引用），不得保留被删简历/岗位正文、Token、密码、邮箱或完整请求内容。
- [ ] 审计记录建议最多保留 180 天后自然过期；具体期限必须由法务、隐私政策和适用地区法规最终确认。法律保全例外必须记录保全依据、范围、责任人和解除时间，不能借此保留无关正文。
- [ ] 普通备份建议保留 30 天后自然过期。恢复旧备份后，必须依据删除墓碑重新执行已完成的删除，不能因恢复而让已删除账户重新可见；备份清理、恢复和墓碑重放都要留有不含正文的结果证据。
- [ ] 删除流程可以记录成功、部分失败、重试和最终清除结果，但不能为“方便排障”保留被删正文、提取文本、岗位全文、凭证或可逆的完整身份映射。
- [ ] 在实现、独立恢复演练和法律文件定稿前，本方案只能作为设计状态披露；不得宣称已经提供账号级删除或完整 GDPR 擦除能力。
