# 第五轮 2A 第二次返工：投递/插件后端剩余并发、状态与实际可用性问题

## 执行角色与边界

你是 Claude Code CLI + DeepSeek 受限执行者。Codex 已完成本轮复核与决策；只实现本文件列出的修正、测试和验证，不重新设计产品。

- 项目：`C:\Users\10578\Documents\AI-JobPilot-Cloud`
- 当前分支：`feature/ai-matching-delivery`
- 保留工作区全部现有修改，它们属于第五轮 2A；不要回退或覆盖无关内容。
- 不修改 `chrome-extension/`、`front/`；不执行 Git commit/push/PR；不发布、部署或执行破坏性操作。
- 不改 V1-V5；V6 尚未提交，可以继续修正。
- 只使用虚构测试数据；不要输出或记录 Token、绑定码、Cookie、账号、完整 URL query、密钥或请求体。

## 复核结论

上一轮已经正确实现并必须保留：Spring Authentication credentials 为 null、数据库内固定长度 Token hash 比较、异常日志不输出消息/堆栈、插件终态行锁、领取时绑定设备、插件四个写接口的 Idempotency-Key、插件转换与审计同事务、证据白名单、自由文本脱敏、Scope 隔离、绑定事务原子性以及现有并发测试。

但 Codex 复核发现以下剩余问题，当前批次不能验收。必须修复根因并补回归测试。

## 1. Redis 绑定码活动数量逻辑错误

`PluginBindCodeService.CREATE_SCRIPT` 当前执行：

```lua
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
```

但 `ARGV[1]` 是**新码的 expiresAt**，不是当前时间。创建第二个码时，所有已有码的到期时间通常都早于新码，因此会被错误移出索引，而对应 value key 仍在 TTL 内有效。结果是：

- 用户索引虚假变小；
- `maxActiveBindCodes` 实际失效；
- 有效旧码脱离索引，消费后也无法证明索引一致性。

修正 Lua 参数协议：清理分数上限必须是服务器当前 epoch millis；新成员的 score 才是新码 expiresAt。活动码上限、最老淘汰、value key 写入和 idem 响应写入仍在同一 Lua 脚本。

新增真实 Redis 集成测试，不得只测同一幂等键：

1. 同一用户用 4 个不同 Idempotency-Key 连续创建，配置上限为 3；索引大小始终为 3；最老 value key 已删除，后三个仍存在且可消费。
2. 在尚未过期时创建第二/第三个码，不会把第一个提前从索引删除。
3. 消费任一有效码后，value、attempt、用户索引成员原子清理；其他有效码不受影响。
4. 保留现有“同一 key 并发只得到一个 code”的测试。

若实现包含随机码碰撞处理，必须安全重试且不能覆盖其他用户仍有效的码；若不调整此项，不要扩大范围。

## 2. Web 状态更新的并发与幂等仍会返回 500/错误冲突

### 2.1 `UPDATE ... RETURNING` 零行处理

`DeliveryRepository.updateGreeting/confirmTask/skipTask` 使用 `JdbcTemplate.queryForObject(UPDATE ... RETURNING)`。零行时会抛 `EmptyResultDataAccessException`，不会返回 null；当前 `return updated != null` 的失败分支实际上不可达。预读与更新之间发生并发时会落入 500。

修正为以下任一可靠方式：

- 捕获 `EmptyResultDataAccessException` 并返回 false；或
- 使用能明确返回更新行数的写法。

最终必须映射成既有 `RESOURCE_VERSION_CONFLICT`/`INVALID_STATE_TRANSITION`，不能 500。

### 2.2 confirm/skip 的真正并发幂等

相同请求并发进入时，两者可能都先读不到事件。必须在租户事务内锁定任务行（例如新增 `findByIdForUpdate`），再检查已有事件、版本、状态并更新。要求：

- 两个完全相同的 confirm 并发：两者都得到同一成功结果，只写一次领域事件、状态只递增一次、审计按真实转换只写一次；
- skip 同理；
- 同一 Idempotency-Key 用在 confirm 与 skip（或相同操作但不同 payload）时稳定返回 409 `IDEMPOTENCY_CONFLICT`，不依赖唯一索引抛异常形成 500；
- `updateGreeting` 的并发旧 version 稳定返回 409，不返回 500；它没有 Idempotency-Key，不需要添加。

当前 Web Controller 把 confirm/skip 审计放在 Service 事务外，导致幂等重放重复审计。把 `DELIVERY_TASK_CONFIRMED`、`DELIVERY_TASK_SKIPPED` 的审计放到与真实状态转换相同的事务内，且重放不重复。传入的审计字段只能是既有稳定白名单；不要记录 reason/greeting/URL/幂等原文。若为了保持 Controller 结构需要传递安全的 RequestMetadata，可作最小调整。

## 3. 数据库状态约束和恢复语义仍不完整

### 3.1 强化 V6 CHECK

当前 CHECK 仍允许直接制造以下不一致行：

- `PENDING_CONFIRMATION` 带 assigned device 或 executionId；
- `SKIPPED/CANCELLED` 带旧 confirmedAt/confirmedBy、assigned device 或 executionId；
- `LEASED` 没有 assigned device；
- 非执行/历史结果状态携带无意义 executionId。

按状态机增强约束，至少确保：

- `PENDING_CONFIRMATION`：confirmedAt/by、assignedDeviceId、lease 三件套、executionId 均为空；
- `CONFIRMED`：确认字段存在，lease 三件套和 executionId 为空；assignedDeviceId 可空或为指定活动设备（活动性由服务保证）；
- `LEASED/EXECUTING`：确认字段和 assignedDeviceId、lease 三件套都存在；`EXECUTING` 还必须有 executionId；
- `SKIPPED/CANCELLED`：确认字段、assignedDeviceId、lease 三件套、executionId 均为空，finishedAt 存在；
- `SUCCEEDED/FAILED` 保留执行归属与 executionId 以支持历史/幂等重放；`SUCCEEDED` 终态不倒退；
- `PAUSED` 可保留本次执行归属和 executionId，但无租约。

不要制定会破坏现有合法流转的互相矛盾约束。补直接 INSERT/UPDATE 被 CHECK 拒绝的数据库测试。

### 3.2 过期租约必须释放设备

任务书要求过期租约“释放设备/lease”。`recover_expired_delivery_leases` 当前清 lease/execution，却保留 `assigned_device_id`。修正为：

- 未满尝试上限回 `CONFIRMED` 时 `assigned_device_id=NULL`，让任一本用户匹配 capability 的活动设备可再次领取；
- 达到上限转 `FAILED` 时也释放 `assigned_device_id`；
- 相关 CHECK、返回和事件保持一致。

新增测试：设备 A 租约过期恢复后，设备 B 能领取；达到上限的 FAILED 无 assigned device。

### 3.3 修改已确认内容必须失效确认

Boss 任务在 `CONFIRMED`、`PAUSED` 或可重试 `FAILED` 状态修改 greeting，都属于改变实际执行内容：回到 `PENDING_CONFIRMATION`，清 confirmedAt/by、assignedDeviceId、lease/execution、finished/error，写 `GREETING_UPDATED` 和 `CONFIRMATION_INVALIDATED`，用户必须重新逐条确认。`PENDING_CONFIRMATION` 编辑仍保持该状态。

非重试 FAILED（例如 JOB_CLOSED）不应通过编辑 greeting 绕过终态业务限制；返回稳定状态错误。智联继续 `GREETING_UNSUPPORTED`。

## 4. 真实 Boss/智联岗位详情链接兼容

当前 URL 白名单过窄，会拒绝项目已有实际采集形态：

- 项目测试/DTO 已出现 Boss `/web/geek/job_detail/<id>`；当前只接受 path 以 `/job_detail/` 开头。
- 项目现有 `ZhilianController.isZhilianJobLink` 还识别 `/job/` 与 `jobs.zhaopin.com` 的职位详情；当前新实现只识别 `jobdetail|positiondetail|job_detail`。

在保持安全边界的前提下统一 URL 规范化：

- 仍仅 HTTPS、无 userinfo/自定义端口、host 严格为 `zhipin.com`/子域或 `zhaopin.com`/子域；恶意标签边界拒绝；返回始终去掉 query/fragment；
- Boss 接受 `/job_detail/<id>` 和 `/web/geek/job_detail/<id>`；拒绝 `/zhaopin/`、`/web/geek/job` 搜索和首页；
- 智联接受已有受控详情路径 `jobdetail|positiondetail|job_detail|/job/<id>`；对 `jobs.zhaopin.com` 的历史详情 URL只允许非空、可识别的职位文件/详情路径，不能因 host 是 jobs 就放行首页或搜索页；
- 路径必须有实际 ID/slug，不能只到目录；禁止 path 规范化绕过（如 dot segment、编码后斜杠/反斜杠导致的伪匹配）；
- 客户端 pageUrl 和数据库 jobUrl 使用同一核心 host/path 判断；数据库合法 query 可剥离，客户端若继续采取更严格的 query 拒绝可以保留，但必须在测试中说明。

新增单元/集成测试覆盖上述允许与拒绝样例。不要联网访问招聘平台。

## 5. 审计 IP 哈希必须使用现有带密钥指纹服务

`PluginService.bindWithOwner` 当前用普通 SHA-256 计算 `ipHash` 并写入 `audit_logs`。项目已有 `SecurityFingerprintService`，使用服务端 `AUTH_HASH_PEPPER` 做 HMAC，且 `AuditLogService` 已采用它。

- 注入并复用 `SecurityFingerprintService.hash(remoteAddress)` 生成审计 `ip_hash`；
- 匿名绑定 Redis IP 限流 key 也优先复用同一个带密钥哈希，避免可字典反推的普通 IP SHA-256；
- installationId、Token、业务幂等哈希仍按现有设计，不要误改成审计 HMAC；
- 测试确认审计不含原 IP，且等于现有服务按测试 pepper 计算的指纹，而不是普通 SHA-256。

## 6. 运行配置必须在 Docker API 服务中真实生效

`.env.example` 和 `application-api.yaml` 已新增 `PLUGIN_*`/`DELIVERY_*`，但 `docker-compose.yml` 的 `api.environment` 没有透传，用户修改 `.env` 时容器里不会生效。

只给 `api` 服务补非敏感透传与安全默认值：

- `PLUGIN_BIND_CODE_TTL`
- `PLUGIN_MAX_ACTIVE_BIND_CODES`
- `PLUGIN_TOKEN_TTL`
- `PLUGIN_MAX_DEVICES_PER_USER`
- `PLUGIN_MIN_EXTENSION_VERSION`
- `DELIVERY_LEASE_SECONDS`
- `DELIVERY_MAX_ATTEMPTS`

不要把 Token/绑定码/固定秘密加入环境变量。`docker compose config --quiet` 必须通过。

同时让 `PLUGIN_MIN_EXTENSION_VERSION` 的非空配置也使用严格数字版本格式；非法配置应在启动绑定阶段被拒绝或明确视为配置错误，不能静默等价为“禁用最低版本检查”。空值仍表示不启用最低版本。

## 7. 明文 Token 成功响应禁止缓存

`POST /api/plugin/bind` 返回仅此一次的明文 Token，但成功响应当前没有显式 `Cache-Control: no-store`。给绑定成功响应加 `Cache-Control: no-store`（可同时加 `Pragma: no-cache`）；不能把 Token 放入日志、异常或审计。补 HTTP 测试确认头存在。

## 8. 验证与验收

必须保留并通过上一轮全部测试，新增以上回归测试。执行：

```powershell
.\gradlew.bat clean test --console=plain
docker compose config --quiet
git diff --check
node scripts/validate-repository-hygiene.mjs
```

验收标准：

1. 全量测试 0 failures/0 errors；不得通过删测试、降低断言或 `@Disabled` 规避。
2. Redis 用 4 个不同 key 的活动码上限测试能在旧脚本下失败、修复后通过。
3. Web confirm/skip 并发重放只有一次状态/领域事件/审计；跨操作复用 key 为 409；更新零行不再 500。
4. 状态 CHECK 拒绝伪造不一致字段；租约恢复后释放 assigned device 并允许另一设备领取。
5. Boss/智联允许项目实际详情 URL，首页/搜索/恶意 host/dot-segment/编码绕过均拒绝。
6. 插件绑定审计 IP 使用现有 HMAC 指纹；绑定 Token 响应 `Cache-Control: no-store`。
7. Docker compose 中配置可见且不新增秘密。
8. 最终报告列出文件、测试总数、失败/跳过数、每项验收对应证据；不执行 Git 操作。
