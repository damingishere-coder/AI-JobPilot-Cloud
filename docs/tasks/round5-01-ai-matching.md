# 第五轮任务 1：基于第四轮岗位池实现 Cloud AI 匹配

## 目标与背景

当前分支由第四轮 `feature/resume-preferences-job-pool` 创建。第四轮已经实现：

- Cloud Session 用户认证与 `CurrentUser`；
- `TenantContextExecutor` + PostgreSQL RLS；
- 加密简历、异步解析和当前简历；
- 版本化 `job_preferences`；
- `job_posts` 统一岗位池、`GET /api/jobs`、`GET /api/jobs/{id}` 和 `/jobs` 基础页；
- 独立 `api` 与 `worker` Profile。

本任务只完成第五轮第一批：在现有岗位池上增加可追溯、可重试、用户隔离的 AI 匹配后端。不要重建 `job_posts`，不要实现投递任务、插件绑定、真实投递或大规模前端改版。

## 必须实现的范围

### 1. V5 增量数据库迁移

新增 `V5__ai_job_matching.sql`，不得修改 V1-V4：

1. 给 `app.job_preferences` 增加正式字段，不得塞进 `extra_filters`：
   - `review_threshold smallint NOT NULL DEFAULT 60`
   - `priority_apply_threshold smallint NOT NULL DEFAULT 65`
   - `apply_threshold smallint NOT NULL DEFAULT 75`
   - CHECK：`0 <= review_threshold <= priority_apply_threshold <= apply_threshold <= 100`。
2. 新建 `app.job_matches`：
   - UUID 主键、`user_id`；
   - 同用户复合外键关联 `job_posts`、`resumes`、`job_preferences`；
   - `status`: `PENDING|PROCESSING|SUCCEEDED|FAILED`；
   - `score` 0-100；`decision`: `APPLY|REVIEW|SKIP`；
   - `summary`、`strengths jsonb`、`risks jsonb`、`greeting`；
   - `model_provider`、`model_name`、`prompt_version`、`input_fingerprint char(64)`；
   - `input_tokens`、`output_tokens`、`duration_ms`；
   - `attempt_count`、`next_attempt_at`、`lease_token`、`lease_until`；
   - `error_code`、脱敏 `error_message`、`started_at`、`completed_at`、`version`、时间戳；
   - 唯一 `(id,user_id)` 和 `(user_id,input_fingerprint)`；岗位最新结果、队列状态需要的索引。
3. 新建 `app.job_match_outbox`，保存可靠的匹配入队事件：UUID/identity 主键、`user_id`、`job_match_id`、唯一 `event_key`、`status PENDING|PUBLISHED`、尝试数、可用时间、租约、发布时间和时间戳；启用 RLS 和复合关联。
4. `job_matches`、Outbox 都启用 RLS，策略沿用 `app.current_user_id()`。
5. 增加最小权限的 `SECURITY DEFINER` 函数，用于：
   - API/Worker 跨租户扫描时领取一条 Outbox；
   - 按租约标记 Outbox 已发布或稍后重试；
   - Worker 按消息中的 `user_id + match_id` 原子领取一条 PENDING/租约过期任务；
   - 函数必须固定 `search_path`、关闭 row_security、校验参数和租约，撤销 PUBLIC 并只授权 `${app_role}`。
6. 给审计动作白名单补充：`JOB_ANALYSIS_REQUESTED`、`JOB_ANALYSIS_SUCCEEDED`、`JOB_ANALYSIS_FAILED`、`JOB_ANALYSIS_REUSED`。

### 2. 求职目标阈值

扩展现有 `PreferenceModels`、Repository、Service 和 API 返回：

- 请求与响应增加 `reviewThreshold`、`priorityApplyThreshold`、`applyThreshold`；
- 兼容旧前端：三个字段在请求中允许缺省，缺省时沿用当前版本已有值；首次创建时使用 60/65/75；
- 保存新版本时把阈值复制为正式列；
- 服务端验证顺序关系并返回字段级 `VALIDATION_ERROR`；
- 现有其他偏好字段和乐观锁语义保持不变。

### 3. Match API 与岗位查询扩展

在 `com.getjobs.cloud.match` 等 Cloud 包中新建清晰的 Controller/Service/Repository/Models：

- `POST /api/jobs/{id}/analyze`
  - JSON `{ "force": false }`，需要 Session/CSRF 和 `Idempotency-Key`；
  - 当前简历必须存在、`is_current=true`、未删除且 `parse_status=PARSED`；
  - 当前求职目标必须存在；
  - 岗位必须属于当前用户；
  - 根据岗位内容、简历 ID + `text_version`、偏好 ID + version、模型名和 Prompt 版本计算 SHA-256 输入指纹；
  - 指纹已有结果时返回该 Match，`reusedExisting=true`，不新增任务；
  - 同事务插入 Match 和 Outbox；并发唯一冲突后读取已有记录；
  - `force=true` 仍使用同一输入指纹，不制造相同输入的重复记录。本轮“强制”仅允许对 FAILED 且输入未变化的记录重新排队；SUCCEEDED 仍复用。
- `POST /api/jobs/batch-analyze`
  - JSON `{ "jobIds": [uuid], "force": false }`；1-50 条、去重；
  - 只处理当前用户能看到的岗位，不泄露其他用户 ID；逐项返回 accepted/reused/error。
- `GET /api/jobs/{id}/match` 返回最新 Match；找不到返回 `MATCH_NOT_FOUND`。
- 扩展现有 `GET /api/jobs` 查询参数：`matchDecision`、`matchStatus`、`minScore`；保持排序白名单。
- 将现有 `JobSummary.latestMatchSummary`、`deliveryTaskStatus` 和 `JobDetail.latestMatch` 从 `Object`/null 改为明确类型。投递尚未实现，`deliveryTaskStatus`/`deliveryTask` 继续是可空的明确占位类型，不创建假数据。
- 所有 Controller 不接受 `user_id`；所有数据库读写必须在当前用户事务/RLS 上下文中进行。

建议响应结构：

- 排队结果：`{ matchId, jobId, status, queuedAt, reusedExisting }`；
- Match：`{ id, jobId, resumeId, preferenceId, status, score, decision, summary, strengths, risks, greeting, priorityCompany, model, usage, error, attemptCount, createdAt, completedAt }`；
- 推荐等级必须由服务端阈值计算，而不是信任模型 decision。

### 4. Redis Streams 与 AI Worker

实现 Cloud 专用、可测试的队列与 Worker，不得把旧本地版 Service 注册进 Cloud：

- Stream 名称默认 `ai-jobpilot:job-match`，Consumer Group 默认 `job-match-workers`，均可配置；
- API Profile 定时从 Outbox 领取事件并 `XADD`，成功后标记 PUBLISHED，失败使用有界退避；
- Worker Profile 使用 Consumer Group 读取，数据库领取成功后分析；消息重复或 Match 已终态时安全 ACK；
- PostgreSQL 是事实来源：增加低频 fallback 扫描/领取 PENDING 或过期 PROCESSING，Redis 短暂丢消息或重启不能永久卡住任务；
- 最多 3 次 AI 尝试；429、5xx、网络和超时可重试，配置缺失、输入缺失、输出 Schema 非法直接或最终 FAILED；重试需有界退避；
- Worker 崩溃后租约过期可以恢复；重复消费不得覆盖 SUCCEEDED；
- Worker 使用 `TenantContextExecutor` 进入消息对应用户上下文后读取岗位、加密简历文本和偏好；不得绕过 RLS 做普通业务查询；
- 使用现有 `DataEncryptionService` 解密提取文本，AAD 必须复用简历既有规则；必要时只把 AAD helper 调整为公共纯函数，不能放宽简历 Web API；
- AI Key 只由 Worker Secret 注入，API 容器不得挂载该 Secret。

### 5. AI Provider、Prompt 和隐私

- 新建 Cloud 专用 `AiMatchProperties` 和可替换接口 `AiMatchClient`；生产实现使用 JDK `HttpClient` 调用一个 OpenAI Chat Completions 兼容端点；禁止依赖用户提供 Key；
- 默认 `enabled=false`；配置至少包含 base URL、model、provider、prompt version、连接/请求超时；Key 从 `ai_api_key` configtree 或 `AI_API_KEY` 读取；
- Compose 只给 `ai-worker` 添加 `ai_api_key` Secret；`.env.example` 只留空占位，启动脚本不得生成虚假生产 Key；未配置时任务给出中文可理解的 `AI_NOT_CONFIGURED`，日志不打印 Key；
- Prompt 只发送岗位必要字段、当前偏好和脱敏后的简历。至少脱敏手机号、邮箱、18 位身份证号；日志、审计、错误和数据库不得保存完整 Prompt、完整简历或原始模型响应；
- 模型 JSON 只允许：`score`、`summary`、`strengths`、`risks`、`greeting`。严格校验：score 0-100 整数；summary 必填且有限长；strengths/risks 最多各 5 条且单项限长；greeting 最多 60 个 Unicode code point；未知字段拒绝；
- 模型不能决定推荐级别。Worker 按当前 Match 已固定的偏好版本阈值计算：优先公司（公司名与 `preferredCompanies` 做规范化包含匹配）达到优先线为 APPLY；其他公司达到普通线为 APPLY；达到 review 线为 REVIEW；否则 SKIP；
- 记录 provider/model/prompt version、token 用量（响应有则记录）、耗时和脱敏错误码；不保存 raw response。

### 6. 测试、配置与文档

- 扩展 Testcontainers/Flyway 集成测试：V5 迁移、CHECK/唯一/复合 FK、A/B 用户 RLS、无租户上下文不可见、窄函数权限和租约；
- 增加 API 集成测试：缺简历/偏好、跨用户 404、CSRF、单条/批量、指纹复用、筛选；
- 增加单元测试：PII 脱敏、严格 JSON、阈值边界/优先公司、重试分类、Outbox 幂等；
- 使用假的 `AiMatchClient`，自动测试不得调用真实 AI；
- 更新 `application-worker.yaml`、`docker-compose.yml`、`.env.example` 和相关文档，敏感值保持为空；
- 运行：
  - `./gradlew.bat test`（Windows；若执行环境使用 Bash 可用 `./gradlew test`）
  - `docker compose config --quiet`
  - `git diff --check`

## 非目标

- 不创建或修改 `delivery_tasks`、插件设备/Token、插件写入 API；
- 不改 Chrome 扩展；
- 不实现真实投递；
- 不重写前端 `/jobs`，只允许为后端类型编译兼容做最小调整；完整前端放到后续任务；
- 不修改 SQLite 迁移或旧 `com.getjobs.application` 本地流程；
- 不做额度/付费；
- 不执行 Git add/commit/push，不创建 PR，不部署，不运行生产迁移，不删除数据或历史文件。

## 可验收标准

1. V1-V5 在空 PostgreSQL 迁移成功，V4 表不被重建或破坏。
2. A 用户无法读取、复用、筛选或触发 B 用户的岗位/Match；数据库 RLS 和复合外键均能阻止越权。
3. 相同输入指纹的重复/并发分析只有一条 Match 和一个有效入队事件。
4. Redis 消息重复、Redis 短暂不可用和 Worker 租约过期都不会丢失 Match 或重复覆盖终态。
5. 模型异常输出不会变成 APPLY；推荐等级只由固定的偏好版本阈值计算。
6. API 容器没有 AI Key；日志、审计和数据库没有完整简历、Prompt、raw response 或 Secret。
7. 现有第四轮简历、偏好、岗位 API 测试继续通过，新增测试覆盖上述关键路径。

实现结束时请报告：改动文件、架构摘要、运行的命令及结果、未完成项或风险。不要自行扩大范围。
