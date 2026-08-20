# 第五轮任务 1 最终收口：补齐行为缺口和专项测试

## 目标

基于当前未提交实现做最后一次小范围返工。主体 Outbox、复合外键、DB fallback 已通过 Codex 静态验收，不得重写架构，不得开始投递/插件/前端。完成下面每一项，不得把“必须测试”解释为后续任务。

## 必修行为

### 1. 偏好阈值继承

- `PreferenceService.update` 必须在锁定并读取 current 后再规范化阈值。
- 首次创建：缺省为 60/65/75。
- 更新：三个字段分别处理；哪个缺省就继承 current 对应字段，明确传入的字段才覆盖。
- 保留版本冲突、字段验证和其余偏好语义。

### 2. Match API 校验与审计

- 批量 body 的 `jobIds` 必须为 1-50；null/空数组返回 400 `VALIDATION_ERROR`，不能返回空成功。
- 单条与批量成功调用后写审计：
  - 新建或 FAILED 强制重排：`JOB_ANALYSIS_REQUESTED`；
  - 指纹复用：`JOB_ANALYSIS_REUSED`；
  - Worker 终态失败：`JOB_ANALYSIS_FAILED`；
  - 已有成功审计保持。
- API 审计沿用 `AuditLogService` + `RequestMetadata`，只写 match/job/status 等非敏感摘要；Worker 沿用 `AuditWriter`。不得记录简历、Prompt、模型 raw response、Secret 或原始错误正文。
- 批量审计只为 accepted 项写，每项动作依据 `reusedExisting`；跨用户 rejected 不写泄露性详情。

### 3. Worker 尝试次数、ACK 与配置

- `AiMatchProperties` 增加并实际使用：`consumerName`（空时生成随机后缀）、`leaseSeconds`、`maxAttempts`、`retryBaseDelaySeconds`、`retryMaxDelaySeconds`，有安全边界/默认值；不得只增加未使用字段。
- 修改 DB claim 函数参数，使普通 claim 和 fallback claim 在数据库层遵守传入的最大尝试数，`attempt_count >= maxAttempts` 不得再次调用 AI。租约过期也不得产生第 4 次 AI 调用。
- retryable 失败成功写入 `PENDING + next_attempt_at` 后，原 Redis 消息应 ACK，因为 PostgreSQL fallback 是事实恢复路径，不能永久留在 PEL。若数据库状态转换失败才不 ACK。
- 非 retryable、达到最大次数、缺配置/输入/非法模型输出进入 FAILED 后写失败审计；重复消息不得覆盖终态。
- Outbox publisher 的失败延迟依据 outbox `attempt_count` 做有界退避；为此 claim 返回 attempt number。API/Worker 两个 publisher 如保留，必须共用相同安全配置和数据库租约语义。

### 4. AI 输出严格性与隐私

- `usage` 整体缺失是允许的：`inputTokens/outputTokens` 返回 null（原规格为“响应有则记录”）；如果 usage 存在但字段类型不是非负整数则拒绝。
- `choices[0].message.content` 必须是文本；顶层匹配结果必须是 JSON object。
- `strengths/risks` 若超过 5 项、包含非字符串项、单项超过 200 Unicode code point，均拒绝，不得静默截断/忽略。
- summary 超过 2000 Unicode code point、greeting 超过 60 Unicode code point均拒绝，不得静默截断。
- 未知字段、score 非整数/越界、空 summary 继续拒绝；非法输出必须是 non-retryable，绝不能形成 APPLY。
- 保存前 PII 二次脱敏保留；日志和数据库错误仅保存稳定错误码及通用中文说明，不拼接任意内部异常 message。

### 5. Compose/CI 配置

- API 容器继续不挂载 `ai_api_key`；`OpenAiMatchClient` 继续只在 Worker Profile。
- `.github/workflows/ci.yml` 的两处 `.secrets` 准备步骤都创建空的 `.secrets/ai_api_key`，只为 Compose 文件存在性校验，不能写假 Key。
- `application-worker.yaml` 补齐新增属性默认配置；`.env.example` 保持 Key 为空。

## 必须新增的专项测试

至少创建以下独立测试文件或等价覆盖，测试名称应清楚表达行为：

1. `OpenAiMatchClientTest`：用本地 JDK `HttpServer` 或可注入 fake transport，绝不联网；覆盖合法响应、usage 缺失、usage 类型错误、未知字段、非字符串数组项、数组超限、summary/greeting 超限、score 非整数/越界、PII 输入脱敏。
2. `MatchWorkerTest`：Mockito/fake repository/client；覆盖普通/优先公司阈值边界 59/60/64/65/74/75、模型输出 PII 二次脱敏、retryable 在第 1/2 次安排退避且调用状态转换、达到最大尝试 FAILED、非法输出不形成 APPLY。若私有方法难测，提取包级纯函数/小组件，不要用反射。
3. `MatchOutboxPublisherTest`：mock Redis/repository；成功路径必须先 XADD 后 confirm；XADD 失败 release 且不 confirm；错误租约由 Repository/SQL 测试验证。
4. 扩展 `PostgresFlywayIntegrationTest`：实际断言三个 Match 复合 FK、Outbox 复合 FK、指纹唯一、无租户不可见、Outbox claim 后仍 PENDING、错误租约不能 confirm/release、正确租约 confirm、Match retry 的 `next_attempt_at`、fallback/普通 claim 最大尝试限制、FAILED force 重排只有一条 Match 且产生新 Outbox。
5. 扩展 `AuthSystemIntegrationTest` 或新建同级 Testcontainers API 测试：至少覆盖缺简历、缺偏好、跨用户 404/不可见、缺 CSRF 被拒、缺/非法 Idempotency-Key、空批量 400、单条排队、重复指纹复用、FAILED force 重排、`matchDecision/matchStatus/minScore` 筛选、偏好阈值缺省继承。可通过测试 JDBC 准备岗位/简历/偏好/Match；不得调用 Worker 或真实 AI。

如果 Testcontainers 因 Docker 环境被 JUnit 跳过，仍要确保纯单元测试运行，并在报告中列出跳过数；不要删掉或放宽测试。

## 验收命令

- `./gradlew.bat clean test --console=plain`
- `docker compose config --quiet`
- `git diff --check`
- `node scripts/validate-repository-hygiene.mjs`

## 限制

- 不实现 delivery task、插件、真实投递或完整前端。
- 不修改 V1-V4、SQLite 和旧本地模式。
- 不执行 Git add/commit/push/PR，不部署、不删除文件，不接触真实 Secret。
- 不改动任务范围外的用户文件。

完成报告必须逐条对应以上行为和测试；任何未完成项都要明确列出，不得宣称全部通过。
