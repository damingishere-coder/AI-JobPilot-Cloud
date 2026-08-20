# 第五轮任务 1 返工：修复 AI 匹配可靠性、租户边界和测试缺口

## 背景

上一轮执行已产生未提交的 V5、Match API、Redis Worker、AI Client 等代码。Codex 验收未通过。本任务是在现有未提交实现上返工，不得回退 `docs/tasks/round5-01-ai-matching.md` 或第四轮代码，不得开始投递任务、插件和前端改版。

## 已确认的阻断问题

1. Outbox 的 claim 函数在 Redis `XADD` 前就把记录标成 `PUBLISHED`；进程在两步之间崩溃会永久丢消息。
2. API 在业务事务提交前直接 `XADD`，Worker 可能先读到尚未提交的 Match；同时绕开了可靠 Outbox 的单一发布路径。
3. retryable AI 异常把 Match 重置为 `PENDING` 后 ACK 原消息，却没有新 Outbox/Redis 消息；任务会永久卡住。`next_attempt_at` 也未实际参与有界退避。
4. Worker 没有数据库 fallback 领取 `PENDING`/租约过期的 `PROCESSING`；死消费者的 Redis PEL 也未恢复。终态重复消息 claim 失败后不 ACK，会永久滞留。
5. `force=true` 对 FAILED 输入会尝试插入同一指纹的新行，触发唯一约束后仍返回 FAILED，实际无法重排。
6. `batchAnalyze` 在租户事务外先调用受 RLS 保护的 `jobs.find`，会把本用户岗位误判为不可见；空数组也未按 1-50 校验。
7. 同一岗位允许不同输入版本产生多条 Match，但 `findByJob`/`findByJobIds` 没有选最新记录，可能抛 `IncorrectResultSizeDataAccessException` 或返回旧结果。
8. 偏好更新缺省阈值总是回落 60/65/75，而不是沿用当前版本。
9. V5 没有按 `(id,user_id)` 建立 Match 到岗位/简历/偏好的复合外键；Outbox 也没有到 Match 的复合外键，且缺少发布尝试数、下次可用时间和失败退避字段。
10. `JobModels` 的 `latestMatchSummary/latestMatch/deliveryTaskStatus/deliveryTask` 仍是 `Object`，没有完成强类型化。
11. Redis stream/group/consumer 等关键值是硬编码；AI Client 不应在 API Profile 注册；API 容器不得获得 AI Secret。
12. 只有一个迁移测试片段，没有 API、脱敏、严格输出、阈值边界、幂等、Outbox/重试等关键测试，无法支撑验收。

## 返工要求

### A. 修正 V5 数据模型和最小权限函数

- 继续只修改未发布的 `V5__ai_job_matching.sql`，不得修改 V1-V4。
- `job_matches` 使用以下复合外键：
  - `(job_post_id,user_id) -> job_posts(id,user_id)`；
  - `(resume_id,user_id) -> resumes(id,user_id)`；
  - `(preference_id,user_id) -> job_preferences(id,user_id)`。
- `job_match_outbox` 使用 `(job_match_id,user_id) -> job_matches(id,user_id)`；增加 `attempt_count >= 0`、`next_attempt_at`、租约和最后一个脱敏错误码所需字段。
- claim Outbox 只能加租约/增加尝试数，状态保持 `PENDING`；只有 Redis `XADD` 成功后，持有正确租约的 confirm 才能改成 `PUBLISHED`。增加按租约释放并设置有界退避的函数。
- Match claim 必须遵守 `next_attempt_at`，支持租约过期恢复；增加无需 Redis 消息的低频数据库 claim-one fallback 函数。
- 增加持租约的 retry 函数：设置 `PENDING`、清租约、写 `next_attempt_at` 和脱敏错误；不能只 reset 后丢失唤醒。
- 增加 FAILED 原输入重排函数：仅同用户、同 Match、FAILED、无有效租约时原子恢复为 PENDING，清理上次结果/错误并写新的 REQUESTED Outbox；SUCCEEDED/PENDING/PROCESSING 必须复用。
- 所有 `SECURITY DEFINER` 固定安全 `search_path`、`row_security=off`、撤销 PUBLIC、只授权 `${app_role}`，并对租约、状态、范围和输入参数进行约束。
- 修正不准确的索引条件和表注释。

### B. 可靠 Outbox、Redis 消费和重试

- API 事务只写 Match + Outbox，不直接 `XADD`，删除 `MatchService` 对 Redis 的依赖。
- 在 API Profile 增加可测试的定时 Outbox publisher：claim -> `XADD` -> confirm；失败 release/retry，指数或分段有界退避。
- Worker Consumer Group 正常消费新消息；数据库 claim 失败时：
  - Match 已 `SUCCEEDED/FAILED` 或不存在时 ACK；
  - 仍被其他 Worker 有效租约占用时本次不覆盖，允许稍后恢复。
- 增加低频数据库 fallback，直接 claim 一条到期 `PENDING` 或租约过期 `PROCESSING` 后处理，确保 Redis 丢消息/PEL 死消费者不会永久卡住。
- retryable 失败且未满 3 次时调用数据库 retry 函数，写入有界 `next_attempt_at`。fallback 必须能在到期后重新领取；不依赖原 Redis 消息再次出现。
- 最多 3 次；429、5xx、网络/超时重试；配置缺失、输入缺失、Schema 非法直接 FAILED；终态不可被重复覆盖。
- stream key、consumer group、consumer name、租约、最大尝试数、轮询间隔均从 `AiMatchProperties` 读取并有安全默认值。

### C. 修正 API、指纹、最新版查询和偏好

- 指纹包含规范化岗位内容、简历 ID + `text_version`、偏好 ID + version、配置的 provider/model/promptVersion。
- `force=false` 复用同指纹任意现有状态；`force=true` 只重排同指纹 FAILED；不得创建相同指纹第二行。
- 单条/批量均在各自正确的当前用户事务 + RLS 上下文完成。批量必须校验 body、`jobIds` 1-50、去重；跨用户 ID 按不可见处理，不泄露归属。
- 对单岗位的“最新 Match”查询按 `created_at DESC, id DESC` 明确取一条；批量摘要也必须每岗位只取最新一条。
- 修复偏好缺省阈值：首次创建默认 60/65/75；更新旧版本时每个缺省字段分别继承当前记录。
- `JobModels` 将匹配字段改为 `MatchModels.MatchSummary/MatchView`；为未来投递新增最小的明确占位 record/enum 或可空 `String` 类型，不得保留 `Object`。
- 完成 REQUESTED/REUSED/SUCCEEDED/FAILED 审计，审计中不得写简历、Prompt、raw response 或 Secret。

### D. AI Client、密钥隔离和输出安全

- `OpenAiMatchClient` 仅注册在 Worker Profile；API Profile 不实例化也不读取 AI Key。
- Compose 仍只给 `ai-worker` 挂载 `ai_api_key`。CI 如需 Secret 文件，仅创建空文件，不写虚假 Key；不得把 Key 提交到仓库。
- 严格处理 usage 缺失；输出字段类型错误、数组非字符串项、未知字段、非法 score/空 summary 必须拒绝，绝不能形成 APPLY。
- 在保存前再次对 summary/strengths/risks/greeting 做手机号、邮箱、18 位身份证脱敏，防止模型回显 PII。
- 日志/错误只保留稳定错误码和中文通用说明，不保存响应正文、完整异常详情、Prompt 或简历。

### E. 必须新增的测试

不要仅让“现有测试通过”，必须覆盖新行为，且不得调用真实 AI：

1. PostgreSQL/Flyway：三个复合 FK、Outbox 复合 FK、阈值 CHECK、指纹唯一、A/B RLS、无租户不可见、Outbox claim 不提前发布、错误租约不能 confirm/release、Match 租约过期/fallback/重试/最多次数。
2. API/Service：缺简历、缺偏好、跨用户不可见、CSRF/Idempotency-Key、单条和 1-50 批量、空批量拒绝、相同指纹复用、FAILED force 重排、match 筛选、同岗位返回最新版。
3. 单元测试：PII 输入和模型输出脱敏、严格 JSON、usage 缺失、阈值 59/60/64/65/74/75 与优先公司边界、可重试分类、Outbox 发布成功/失败状态转换。
4. 测试必须使用 fake/mock `AiMatchClient` 和 Redis mock/容器，不得联网调用真实 AI。

## 验收命令

- `./gradlew.bat clean test`
- `docker compose config --quiet`
- `git diff --check`
- `node scripts/validate-repository-hygiene.mjs`

## 非目标和限制

- 不实现 delivery task、插件 API、真实投递和完整前端。
- 不修改 SQLite/旧本地模式。
- 不执行 Git add/commit/push/PR，不部署、不运行生产迁移、不删除文件。
- 不打印、读取或提交真实 Secret。

完成时逐项报告修复结果、新增测试、命令结果及仍存风险；若任何测试因环境跳过，也必须明确说明。
