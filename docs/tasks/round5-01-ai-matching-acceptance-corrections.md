# 第五轮任务 1 验收纠偏：严格按既定语义修正

## 背景

当前未提交代码已能编译并有专项测试，但 Codex 对照批准任务发现最终报告与代码不一致。本任务只修下面列出的验收缺陷。不得删除或弱化现有测试，不得开始投递/插件/前端。

## 1. AI 响应严格校验必须按规格实现

当前错误：`usage` 缺失被拒绝；summary/greeting/数组项/数组总数超限被静默截断；JSON 解析错误标记为可重试；日志仍打印脱敏后的 raw response 预览。

必须改为：

- 顶层 `usage` 整体缺失或 null：允许，`inputTokens/outputTokens=null`。
- `usage` 存在时必须是 object，`prompt_tokens` 和 `completion_tokens` 都必须存在且为可转 int 的非负整数；浮点数、字符串、负数、缺一个字段均拒绝。
- match content 必须是 JSON object；未知字段、JSON 解析失败、content 类型错误、score/summary/数组/greeting Schema 错误全部是 non-retryable `AI_RESPONSE_INVALID`（解析可用单独稳定错误码，但同样 non-retryable）。
- summary 超过 2000 Unicode code point：拒绝，不截断。
- greeting 超过 60 Unicode code point：拒绝，不截断。
- strengths/risks 超过 5 项、任意项不是 string、任意项超过 200 Unicode code point：拒绝，不截断、不忽略。
- 日志不得输出 raw response 的任何片段，即使脱敏也不可以；只记录稳定错误码/HTTP 状态/异常类型。
- 修正 `OpenAiMatchClientTest`：测试名称和断言必须对应“usage 缺失允许、所有超限拒绝、非字符串项拒绝、浮点 token 拒绝、非法输出 non-retryable”。不得保留 `truncates...` 或“triggers APPLY fallback”这类错误语义测试名。

## 2. 最大尝试次数必须在数据库 claim 时原子限制

当前错误：SQL claim 函数不接收最大尝试数，会把 `attempt_count=3` 再领取成第 4 次，然后 Java 才拦截。

必须改为：

- `claim_match_for_processing` 与 `claim_one_pending_match` 增加 `p_max_attempts integer`；限制安全范围 1-10，并在 UPDATE 前要求当前 `attempt_count < p_max_attempts`。
- Repository 所有调用传入配置的 `maxAttempts`；更新 REVOKE/GRANT/COMMENT 和所有测试 SQL 签名。
- 第 3 次领取允许调用 AI；第 3 次失败后直接 FAILED；数据库绝不产生第 4 次领取/AI 调用。
- Java 的防御性检查可保留为 `attemptNumber > maxAttempts`，但必须写 FAILED 审计；主保证来自 SQL。
- retryable 状态转换成功后，Stream 消息要 ACK（方法返回 terminal/ack decision 应清晰）；转换失败才保留不 ACK。更新错误注释。
- `PostgresFlywayIntegrationTest` 明确把 Match 置为 `attempt_count=maxAttempts`，断言普通 claim 和 fallback claim 都返回 0 行，且计数未增加；再断言 `attempt_count=maxAttempts-1` 能被领取为最后一次。

## 3. Outbox 退避和配置必须真实生效

当前错误：Outbox claim 未返回尝试次数；两个 publisher 仍使用硬编码租约和固定 5 秒退避。

必须改为：

- `claim_match_outbox_publish` 返回本次 `attempt_number`；`OutboxJob` 保存它。
- API `MatchOutboxPublisher` 与 Worker publisher 都使用 `AiMatchProperties.leaseSeconds/retryBaseDelaySeconds/retryMaxDelaySeconds`，按 attempt number 计算相同的有界指数退避。
- 日志不得拼接 Redis/内部异常 message，只记录 matchId 和异常类型。
- `MatchOutboxPublisherTest` 验证 attempt 1/2 的退避增长和上限、配置化 lease 传入、成功顺序 XADD→confirm、失败 release 且不 confirm。

## 4. CI Secret 必须是空文件

当前错误：CI 写了 `sk-ci-placeholder-not-a-real-key`，违背“不得生成虚假生产 Key”。

- `.github/workflows/ci.yml` 两处改成创建空文件，例如 `: > .secrets/ai_api_key`。
- 不得在仓库或测试输出中出现 `sk-` 形式假 Key。

## 5. 岗位筛选必须只依据最新 Match

当前 `GET /api/jobs` 的 `matchDecision/matchStatus/minScore` 用 `EXISTS` 匹配任意历史记录，会让旧 APPLY 命中而最新 SKIP。

- 修改 `JobRepository` 查询，让三个筛选条件都只作用于每个岗位按 `created_at DESC, id DESC` 的最新 Match。
- 保持参数绑定和排序白名单，不拼用户输入 SQL。
- 增加测试：同岗位两条不同指纹记录，旧记录 APPLY/高分、最新记录 SKIP/低分；APPLY/minScore 高值不应命中，SKIP 应命中。

## 6. 补实而不是弱化数据库与 API 集成测试

### PostgreSQL 测试

当前所谓“复合 FK enforcement”主要只测了可见性。必须真正执行失败插入并逐项断言 SQLException：

- A 用户 Match 引用 B 用户 job；
- A 用户 Match 引用 B 用户 resume；
- A 用户 Match 引用 B 用户 preference；
- A 用户 Outbox 引用 B 用户 Match；
- 无 `app.current_user_id` 时 app role 看不到 Match/Outbox；
- Outbox claim 后状态仍 PENDING；错误 lease confirm/release 均 false 且状态/租约不被改变；正确 lease confirm 后 PUBLISHED；
- force requeue 仍只有原 Match 一条，并新增一条有效 Outbox；event key 使用不可碰撞值（不要 epoch 秒）。

### API 集成测试

API 入队不调用 AI Key，不能再以缺 Key 为理由顺延。使用现有 `AuthSystemIntegrationTest` + 测试 JDBC 准备数据，完整覆盖：

- 缺简历 → 428；有 parsed 当前简历但缺偏好 → 428；
- 跨用户岗位单条 analyze → 404，GET match → 404/`MATCH_NOT_FOUND`，不得泄露；
- 缺 CSRF → 403；缺/空/超长 Idempotency-Key → 400；空 batch → 400；
- 单条成功后数据库有且仅一条 Match + 一条 REQUESTED Outbox；重复同输入复用同 matchId，不新增 Match/REQUESTED Outbox，并产生 REUSED 审计；
- 把该 Match 置 FAILED 后 `force=true`：仍是同 matchId、恢复 PENDING、增加 REQUESTED Outbox；
- `GET /api/jobs/{id}/match` 返回明确状态；
- 上一节的最新 Match 筛选场景；
- 已有阈值首次默认/更新缺省继承测试保留。

不得调用真实 Worker/AI。若 API Profile 的定时 Outbox publisher 会干扰数据库计数，在测试属性中用很长轮询间隔或 mock/禁用调度，不要删断言。

## 7. 错误信息与审计

- Worker 保存的 `INTERNAL_ERROR` 使用固定中文通用文案，不拼内部 exception message；日志只记异常类型。
- max-attempt、简历缺失、AI non-retryable、未知运行时最终失败都必须在 `completeMatch` 成功后写一次 `JOB_ANALYSIS_FAILED` 审计。
- 非法 AI 输出绝不计算 decision，也绝不产生 APPLY；修正所有暗示“APPLY fallback”的注释/测试名称。

## 验收命令

- `./gradlew.bat clean test --console=plain`
- `docker compose config --quiet`
- `git diff --check`
- `node scripts/validate-repository-hygiene.mjs`
- `rg -n "sk-ci-placeholder|truncates.*(Summary|Greeting|Array)|triggersApplyFallback" .github src` 应无结果。

## 限制

- 不修改 V1-V4/SQLite/旧本地模式；不实现投递、插件或完整前端。
- 不删除/跳过现有测试，不把明确条目顺延。
- 不执行 Git add/commit/push/PR，不部署、不读写真实 Secret、不删除文件。

完成报告逐项对应 1-7，并明确总测试数、失败数、跳过数。
