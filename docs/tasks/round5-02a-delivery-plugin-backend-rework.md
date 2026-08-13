# 第五轮任务 2A 返工：修复投递并发、插件 Token 与输入安全

## 背景与结论

首版 `round5-02a-delivery-plugin-backend.md` 已实现 V6、插件绑定/Token、投递 API，并自测通过。Codex 复跑 `./gradlew.bat clean test --console=plain` 也通过，但主控代码审查发现多项当前测试未覆盖的安全与一致性缺口，因此首版暂不验收。

本返工只修复现有未提交的 2A 后端实现和测试。不得修改 `chrome-extension/**`、`front/**`、V1-V5、SQLite 或本地旧版；不得 Git add/commit/push。V6 尚未发布，可以直接修正 `V6__delivery_plugin_backend.sql`。

## 1. 必须修复：Token 明文、认证比较和异常日志

### 1.1 明文 Token 不得进入安全上下文

`PluginTokenAuthenticationFilter` 当前用：

```java
UsernamePasswordAuthenticationToken.authenticated(principal, token, authorities)
```

这会把明文插件 Token 放入 Spring Security credentials，违反“明文只在绑定响应返回一次”。改为 `credentials=null`，认证成功后的 principal/authorities 中也不得包含明文或 hash；不得把 Authorization、Token 或 hash 写 MDC、异常、日志、`toString()`、审计详情或响应。

### 1.2 取消原始异常消息和堆栈日志

`ApiExceptionHandler.handleUnexpected` 当前记录 `exception.getMessage()` 和完整异常对象。数据库异常可能包含绑定值、Token hash、用户输入或 SQL 细节。恢复为只记录稳定异常类型和 requestId（已有 MDC 可用），不记录异常消息、堆栈或请求体。其他本批新增日志也遵守同一规则。

### 1.3 Token hash 常量时间核验

遵守 `CLOUD_SECURITY.md`：Token 至少 256 bit；数据库不返回 hash；认证比较不得直接以可提前返回的字符串比较作为最终凭证核验。

建议方案：

- 从明文 Token 计算安全的 `token_prefix` 和 SHA-256 hash；
- `authenticate_plugin_token` 用 prefix 定位极小候选集；
- 在 PostgreSQL 函数内部实现固定 64 字符、全长度执行且不提前返回的比较，再返回最小身份字段；
- 函数仍不得返回 token hash；固定 `search_path`、`row_security=off`、撤销 PUBLIC，只授权 app role；
- 无效 Token、同 prefix 但 hash 不同、过期、轮换、设备撤销、账号禁用都要覆盖。

如果选择其他实现，必须同样满足“最终比较常量时间 + hash 不离开安全函数”。不能以“随机 Token 很难猜”为理由跳过已明确的安全要求。

## 2. 必须修复：插件状态转换的原子性和设备归属

### 2.1 finish 函数必须锁行或单语句条件更新

`plugin_task_success/fail/pause` 当前是普通 `SELECT` 后再无状态/version 条件 `UPDATE`。并发 success/fail/pause 可同时通过旧状态检查，迟到的失败可能覆盖已成功终态。

修正为真正原子：

- 可在读任务时 `FOR UPDATE`，然后在持锁事务内检查/更新/写事件；或使用等价的单语句条件更新；
- 同时校验 `user_id`、`assigned_device_id`、task、status、expected version、leaseId、executionId、未过期租约；
- 只有持有租约的当前设备能回传；同用户另一设备即便知道 task/lease/execution 也必须 `LEASE_INVALID` 或统一拒绝；
- `SUCCEEDED` 一旦提交，任何后续 fail/pause/success 不同载荷都不能改写；
- 两个竞争终态只能有一个真实状态转换和一条对应领域事件；
- 所有检查、状态更新、不可变事件在同一数据库事务中。

### 2.2 start 必须绑定实际领取设备

任务 `assigned_device_id IS NULL` 时，成功 start 必须原子写为当前 `p_device_id`；已经指定其他设备时拒绝。执行态数据库 CHECK 应要求 `assigned_device_id IS NOT NULL`。start 的返回和后续回传都以该设备为准。

### 2.3 状态字段数据库一致性

增强 V6 CHECK/更新语义：

- `PENDING_CONFIRMATION` 必须 `confirmed_at/confirmed_by IS NULL`；
- `CONFIRMED/LEASED/EXECUTING/SUCCEEDED/FAILED/PAUSED` 必须已有 `confirmed_at/confirmed_by`；
- `LEASED/EXECUTING` 必须有 lease，`EXECUTING` 必须有 executionId 和 assigned device；
- 从可重试 FAILED/PAUSED 重新确认时清除旧 `finished_at`、lease/execution/error；保留单调递增的 confirmationVersion 和 attemptCount；
- skip 必须按任务书清除旧确认、assigned device、lease/execution，并写正确终态时间；
- 修改已确认 Boss 招呼语回到 PENDING 时清除旧确认和旧设备分配；
- `SUCCEEDED` 终态不可倒退。

### 2.4 租约恢复错误不能被静默吞掉

`DeliveryService.recoverExpiredLeases()` 不得捕获 `DataAccessException` 后伪装成“恢复 0 条”。让异常上抛给 sweeper，sweeper 只记录安全的异常类型并在下轮重试。

## 3. 必须修复：插件写接口幂等与 Scope

### 3.1 所有执行写接口使用 Idempotency-Key

`start/success/fail/pause` 都必须要求合法 `Idempotency-Key`。不仅靠 executionId 判断重放：

- 每个事件存服务端计算的 payloadHash；hash 覆盖可信 user/device/task、幂等键、version、leaseId、executionId 和规范化后的业务载荷；
- 完全相同的重放返回原结果，不新增事件、不重复审计；
- 同 executionId 或同 Idempotency-Key 但不同 resultCode/evidence/error/reason 等载荷必须返回 `IDEMPOTENCY_CONFLICT`；
- 成功重放不能因客户端仍带旧 version 被误判版本冲突；
- 不在事件中存原始 Idempotency-Key。

必要时调整 V6 函数签名和 repository/service/controller。更新函数 REVOKE/GRANT/COMMENT，避免遗留可调用旧签名。

### 3.2 start 同时要求两个 Scope

`POST /api/plugin/tasks/{id}/start` 必须同时拥有 `tasks:read` 和 `tasks:write`。只拥有其中一个的 Token 返回 403。pending 仍只需 read；success/fail/pause 只需 write。

## 4. 必须修复：确认空设备 500 与绑定并发

### 4.1 不指定设备也能逐条确认

`DeliveryService.confirm` 当前把 nullable `assignedDeviceId` 放进 `Map.of`，会触发 NPE/500。改为允许 `assignedDeviceId=null`：确认返回 200，任务进入 CONFIRMED，任何具备相应 capability 的本用户活动设备都可在竞争中领取，胜者 start 时绑定设备。审计/事件详情不得用不支持 null 的 `Map.of`。

### 4.2 绑定码幂等创建必须是 Redis 原子操作

当前 `GET idem -> 生成 -> 注册` 存在并发窗口，相同 user + Idempotency-Key 可生成多个有效码。使用 Lua 或等价 Redis 原子操作：

- 并发相同 key 只生成/保存一个响应，所有调用得到相同绑定码；
- 活动码上限与最老淘汰同一原子操作；
- 一次消费原子；用户索引保持有界且清理已消费/过期成员，不能长期计入活动数量；
- 不记录 code/userId/token；幂等缓存仍受绑定码 TTL 约束。

### 4.3 设备上限并发安全、账号状态校验

`bind_plugin_device` 的 count-then-insert 需按用户串行化（例如锁定 `app.users` 当前用户行），防止两个并发新安装同时越过设备上限。绑定时再次确认账号 ACTIVE；绑定码签发后账号被禁用也不能新建设备/Token。映射为稳定安全错误，不泄露账号细节。

### 4.4 Token 签发与数据库审计不能产生“已提交但明文丢失”

绑定设备、保存 token hash、读取设备结果和 `PLUGIN_DEVICE_BOUND` 审计必须处于同一 PostgreSQL 事务。若审计或读取失败，数据库 token/device 写入回滚，不能返回 500 却留下客户端永远拿不到明文的活动 Token。Controller 不应在事务提交后才做可能抛错的绑定审计。

同理，插件 start/success/fail/pause 的安全审计至少要保证：真实转换和审计处在同一事务；幂等重放不重复写安全审计。不得为此把明文 Token、完整 URL 或自由文本写进审计。

## 5. 必须修复：受信岗位 URL、证据和自由文本

### 5.1 校验服务器返回的 jobUrl，而不只是客户端 pageUrl

当前只验证 request.pageUrl，却把数据库 `job_url` 原样发给插件。插件会实际导航该 URL，因此：

- pending 和 start 返回前都必须校验/规范化数据库 jobUrl；不受信任务不能下发执行；
- 仅 HTTPS、无 userinfo/自定义端口；host 必须是 `zhipin.com`/其子域或 `zhaopin.com`/其子域，严格标签边界；
- BOSS path 必须是 `/job_detail/` 岗位详情类路径；智联必须包含已知岗位详情 path（如 `jobdetail`/`positiondetail`/`job_detail` 等），搜索页/首页不能当详情页；
- 返回给插件时移除 query/fragment，避免跟踪参数或开放重定向参数下发；合法岗位带 query 时可以安全规范化为 origin + path，而不是直接放行原 query；
- `pageUrl` 使用相同 host/path 规则；恶意 host、首页、搜索页、脚本/重定向参数拒绝；
- 不把无效完整 URL 写日志。

### 5.2 evidence 必须是真白名单

当前允许任意正则合法 key，不符合任务书“白名单 evidence”。为本轮插件定义最小固定集合，例如：

- `pageState`: 只允许 `SUCCESS_NOTICE|ALREADY_DELIVERED`；
- `alreadyDelivered`: boolean（可选）。

不需要的字段拒绝；不接受截图、HTML、DOM、页面全文、嵌套对象、数组或 URL。后续扩展只依赖这个稳定集合。

### 5.3 错误/暂停文本脱敏和规范化

- resultCode/errorCode/pause reason 校验后转成规范大写再入库/事件/响应；
- error message/pause message 仅允许短的单行安全摘要，按 Unicode code point 限制；
- 包含 `Cookie`、`Authorization`、`Bearer`、`password`、`token=`、`LocalStorage`、`SessionStorage`、控制字符或疑似完整 URL query 的输入应拒绝或替换为稳定通用摘要，原值不能入库/日志/审计；
- `retryable` 由服务端错误码规则决定；对允许客户端选择的 UNKNOWN_ERROR 也必须定义确定规则或严格验证，不得无约束信任。

## 6. 其他一致性修正

- 回填“最新 SUCCEEDED + APPLY”应显式用 `DISTINCT ON`/窗口函数选每岗位最新 Match，不依赖 `ORDER BY + ON CONFLICT DO NOTHING` 的插入顺序副作用。
- 严格校验 extensionVersion 为支持的数字版本格式；不要把非法段按 0 静默解析后意外放行。
- `POST /api/plugin/bind-code` 和 `POST /api/delivery/tasks` 按既有 API 设计返回 201；若项目统一规范有明确不同约定，可保持一致并在报告说明。
- 不新增 batch confirm/batch delivery、后台静默轮询、服务器 DOM、验证码绕过或平台 Cookie 通道。

## 7. 必须新增/强化测试

除保留现有测试，至少补以下测试；不能只修改断言绕过问题：

### 数据库并发与约束

1. 两连接并发 success 与 fail/pause：只能一个转换成功；若 success 已提交，迟到 fail/pause 不倒退、不新增失败/暂停事件。
2. 未指定设备的 CONFIRMED 任务由两设备并发 start：单赢家，胜者写入 assigned_device_id；败者不能回传；同用户另一设备拿到 lease/execution 也不能 finish。
3. finish 同 version 并发不能覆盖终态；完全相同重放一条事件，不同载荷冲突。
4. 直接插入/更新不一致 confirmed/lease/execution/assigned 字段被 CHECK 拒绝。
5. skip 清确认/设备/租约；FAILED/PAUSED 重新确认清 finished/error 且可再次 start。
6. 并发绑定在设备上限边界不能超过上限；账号禁用后旧绑定码不能签发 Token。

### HTTP/Security/Redis

1. 认证成功后的 Spring Authentication credentials 为 null；响应、日志、审计和异常中不出现测试用 Token 或 hash 哨兵。
2. 同 token prefix 的错误 hash 认证失败；数据库函数不返回 hash 列。
3. 相同 user + Idempotency-Key 并发创建绑定码只得到一个 code；消费一次；活动索引正确清理。
4. confirm `assignedDeviceId=null` 返回 200/CONFIRMED，随后设备领取成功。
5. start 缺 read 或缺 write 任一 Scope 都 403。
6. success/fail/pause 缺 Idempotency-Key 为 400；完全相同重放成功，不同 payload 为 409 `IDEMPOTENCY_CONFLICT`。
7. 无效数据库 jobUrl 不出现在 pending，start 拒绝；合法带 query URL 下发时 query/fragment 已移除；首页/搜索页拒绝。
8. evidence 非白名单、嵌套内容、疑似凭证文本拒绝；规范白名单成功。
9. 插件/Web 双身份隔离、跨用户 404、撤销即时生效、APPLY 自动建单、Boss/智联招呼语规则等现有用例继续通过。

## 8. 验收命令

- `./gradlew.bat clean test --console=plain`
- `docker compose config --quiet`
- `git diff --check`
- `node scripts/validate-repository-hygiene.mjs`
- `rg -n "Cookie|LocalStorage|SessionStorage|password" src/main/java/com/getjobs/cloud/plugin src/main/java/com/getjobs/cloud/delivery`
- 人工确认安全上下文 credentials 不含 Token、异常日志不含 message/stack、V6 所有安全函数固定 search_path/row_security/REVOKE/GRANT、finish 使用锁或等价原子条件。

完成报告必须逐项说明修复、并发测试、测试总数/失败/跳过、未完成项和剩余风险。不得声称“测试通过”即可覆盖上述代码审查项。
