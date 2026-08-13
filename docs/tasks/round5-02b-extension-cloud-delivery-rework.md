# 第五轮 2B 返工：修复扩展唤醒校验、安全随机与持久化恢复缺口

## 执行角色与边界

你是 Claude Code CLI + DeepSeek 受限执行者。Codex 已复核首版实现，认可固定开发 ID、精确 CORS、popup、Cloud API 封装、单任务状态机与 content 隔离方向，但以下问题使本批暂不能验收。

- 只修本文件列出的根因、测试和必要注释；保留当前工作区所有 2B 修改。
- 不重做产品/架构，不修改 V1-V6，不扩展前端，不 Git add/commit/push，不部署或真实访问招聘平台。
- 不降低已有断言、删测试或用静态字符串断言替代行为测试。

## 1. page bridge 必须在进入扩展后台前校验 Cloud 唤醒载荷

当前 `page-bridge.js:isValidPageMessage` 对 `CLOUD_DELIVERY_WAKE` 仍只校验 source/type，没有校验 taskId/requestId。虽然 background 会二次校验，但任务明确要求页面桥也拒绝畸形消息，避免任意网页上下文把无界数据转发进扩展。

修正：

- 对 legacy 消息保持现状；对 `CLOUD_DELIVERY_WAKE` 额外要求：
  - `taskId` 是严格 UUID；
  - `requestId` 非空、最多 128、只含 `[A-Za-z0-9._-]`；
  - 消息只提取/转发 `source,type,taskId,requestId`，不能把额外属性（尤其 Token、URL、greeting、lease、executionId）透传到 background。
- background 继续做独立二次校验，不能因 bridge 校验而删除。
- Cloud wake 的扩展无响应结果也只返回稳定 `success/code/message`，不要透出 `rawMessage` 或未知原始 runtime 错误；legacy 行为可保持兼容。

新增 VM 行为测试：非 UUID、空/超长/非法 requestId、带额外敏感字段的唤醒；前四类不得调用 runtime，合法但带额外字段时 background 收到的对象只有四个允许字段。

## 2. 禁止安全随机降级为 `Math.random`

`cloud-client.js:randomBytes` 在 Web Crypto 不存在时退化成 `Math.random()`，不满足 installationId 256-bit 和 execution/idempotency 安全要求。

- 必须使用 `globalThis.crypto.getRandomValues`；不可用或调用失败时抛出不含内部信息的稳定错误，绝不生成或持久化弱随机值。
- popup 已有 catch，应显示稳定通用错误且不发送 bind；background 应在生成 execution/key 失败时报告 `SECURE_RANDOM_UNAVAILABLE`，不调用 start、不导航、不触发 content。
- 不把原始异常、随机字节、绑定码或 Token 写 console/页面消息。
- 测试无 Web Crypto/`getRandomValues` 抛错两种场景：没有 `Math.random` 兜底，没有 bind/start/导航/content，storage 不出现新 installation/execution 状态。

## 3. 关键持久化失败时不得继续产生不可恢复副作用

当前 background 对 `Cloud.writeExecutionState(...)` 的 boolean 返回值全部忽略：

1. start 前写失败仍会调用 start；
2. start 成功后写 lease/payload 失败仍会导航并点击；
3. finish 前写报告失败仍会发送 finish。

这会让 MV3 Service Worker 中断后丢失 execution/key/lease/report，破坏首版任务的核心验收。

修正为明确检查每次关键写入：

- 初始 `starting` 状态写失败：立即报告 `STORAGE_UNAVAILABLE`（稳定消息），绝不调用 start/导航/content。
- start 成功后 `executing` 状态写失败：绝不导航/content。保留此前 starting 状态（若可读取）以便下次同 task 显式唤醒用相同 start key 重放；报告 `STORAGE_UNAVAILABLE`。不要清除仍有恢复价值的旧 starting 数据。
- content 结果后 `reporting` 状态写失败：不得声称已回传成功；不要生成新的报告 key 后直接丢失。保留现有 executing 状态并报告 `STORAGE_UNAVAILABLE`，等下一次同 task 显式唤醒重新检查页面并形成结果。Boss 已沟通/智联已投递判断应避免重复平台动作。
- `writeExecutionState` 对非法/缺失关键字段必须返回 false，不得把空 executionId/key、无 lease 的 executing/reporting 状态写入 storage。
- `clearExecutionState`/Token 清理可继续 best-effort，但不能在认证失败时保留旧 Token。

加入 storage.local.set 故障注入行为测试，分别精确发生在上述三个写点，断言后续 Cloud/API/导航/content 调用没有越过安全边界。不能只测 helper 返回 false。

## 4. 状态恢复必须遵守实际租约，不能固定 20 分钟提前清理

当前 `EXECUTION_FRESH_MS=20min`，而后端租约可配置到 30min；不同 task 唤醒可能在旧租约仍有效时清理状态并开始新任务。此外 start 响应的 `leaseExpiresAt` 没有持久化。

- `read/writeExecutionState` 增加并严格保存 `leaseExpiresAt`。
- starting（尚不知 lease）状态不得仅因 20 分钟就被另一个 task 自动清除：其 start 可能已在服务端提交但响应丢失。不同 task 保守返回 `PLUGIN_BUSY`，必须先显式恢复原 task 或本机解除绑定。
- reporting 状态含不可丢的实际平台结果，不得因本地固定超时被另一 task 自动清除；不同 task 始终 `PLUGIN_BUSY`。
- executing 状态至少在 `leaseExpiresAt` 及小幅容差前始终视为 busy；不得以早于 lease 的固定窗口清理。
- 同 task 恢复时，若 executing 租约已明确过期，不得再次导航/点击；清理该执行并报告稳定 `LEASE_EXPIRED`，等待用户下一次显式唤醒重新 pending/start。reporting 则先让服务端对原样报告作出 `LEASE_EXPIRED`/成功判断，不自行丢弃。
- 若时间字段畸形/缺失，采取保守 busy/同 task 恢复，不要假设安全清理。

新增测试：

1. 25 分钟前 starting 状态 + 不同 task，仍 busy；同 task 重放原 start。
2. `leaseExpiresAt` 尚未到期，即使 updatedAt 超过 20 分钟，不同 task 仍 busy。
3. executing 租约已过期，同 task 不导航、不 content，清状态并报告 `LEASE_EXPIRED`；随后再次显式 wake 才允许重新 pending。
4. reporting 无论 updatedAt 多旧，不同 task 都 busy；同 task 原样重放报告。

## 5. Boss Cloud 点击前基准 URL 顺序

`deliverCloudManagedOnCurrentPage` 当前先 `clickElement(chatButton)`，后记录 `beforeUrl`；旧路径正确顺序是先记录再点击。同步跳转时会丢失点击前基准。

- 改为点击前保存 `beforeUrl`，再点击、等待结果。
- 补最小行为/源码结构测试，确保赋值出现在 click 前；不要影响已沟通不发 greeting 的规则。

## 6. CORS 端到端预检补强

当前 `PluginCorsConfigurationTest` 只直接调用 `DefaultCorsProcessor`，未证明真实 Spring Security filter chain 中 CORS 位于 Token filter 前。

- 在现有 Testcontainers/Spring API 集成测试或合适的新集成测试中，通过真实 HTTP 对 `/api/plugin/tasks/{uuid}/start` 发 OPTIONS：正确开发 Origin + POST + Authorization/Content-Type/Idempotency-Key 应 200/允许 exact Origin，且不返回 `PLUGIN_TOKEN_INVALID`。
- 错误扩展 Origin 应被拒；实际无 Token GET pending 仍为 401，证明 CORS 不是鉴权。
- 若测试环境启动成本可复用现有 `PluginDeliveryIntegrationTest`，优先最小增量。

## 7. 重新验证

必须运行并报告：

```powershell
node --test chrome-extension/tests/*.test.cjs
node scripts/validate-chrome-extension.mjs
.\gradlew.bat clean test --console=plain
docker compose config --quiet
git diff --check
node scripts/validate-repository-hygiene.mjs
```

验收：0 failures/0 errors；说明真实 tests/pass/fail/skipped 数量。额外确认：

- Cloud 代码没有 `Math.random`、timer 轮询、alarms、storage.sync。
- 所有 start/content/finish 前置关键 storage 写均检查失败分支。
- 不提交私钥、Token、绑定码、Cookie、浏览器缓存。

最终报告逐项对应问题、文件、测试和剩余风险，不执行 Git 操作。
