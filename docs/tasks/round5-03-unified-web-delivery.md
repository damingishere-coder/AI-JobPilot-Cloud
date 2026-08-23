# 第五轮任务 3：统一 AI 岗位页、投递清单与插件设备管理

## 执行角色与边界

你是 Claude Code CLI + DeepSeek 受限执行者。Codex 已完成产品与架构决策，并已验收第五轮 AI 匹配后端、投递状态机与插件后端；Chrome 扩展云端投递批次也会在本任务开始前由 Codex 独立验收。

本任务只实现 Cloud Web 前端及其前端测试，并按本文要求更新与该界面直接相关的文档。不要修改 V1-V6 数据库迁移、Java 后端、Chrome 扩展执行状态机或旧版各平台分析页；不要 Git add/commit/push，不部署，不访问真实招聘平台。

保留工作区已有修改。先读取 `AGENTS.md`、现有 `/jobs`、`/jobs/detail`、`AuthProvider.secureRequest`、`chromeBridge.ts`、`Sidebar`、前端测试与 UI 组件风格，再动手。

## 已批准的产品决策

1. 推荐等级只有 `APPLY / REVIEW / SKIP`，中文分别显示“推荐投递 / 建议复核 / 建议跳过”。推荐等级由后端阈值计算，前端不得自行改判。
2. `APPLY` 完成后后端自动创建待确认任务；`REVIEW` 由用户在岗位详情中手动加入投递清单；`SKIP` 不创建任务。
3. 投递必须逐条确认。禁止批量确认、批量唤醒或页面轮询自动领取。
4. 仅支持 BOSS、智联的 Cloud 投递；BOSS 可编辑招呼语，智联不使用自定义招呼语且编辑控件必须禁用。
5. Web 的服务端确认与插件唤醒是两个阶段：确认 API 成功后任务就是 `CONFIRMED`。插件离线、未绑定、忙碌或超时时不得回滚确认、不得重复发送 confirm、不得自动 skip；界面提示“任务已确认，但插件未接收”，并提供对同一 taskId 的“重新唤醒插件”。
6. 完整岗位分析（summary/strengths/risks/greeting）只在岗位详情及当前选中的投递任务详情中按需读取。`GET /api/jobs` 只有匹配摘要，禁止列表 N+1 请求。
7. 不做静默定时轮询。初次加载、用户点击刷新、用户操作完成和扩展显式事件可以刷新数据。

## 后端契约（以源码为准，不得臆造）

优先读取这些正式类型与 Controller：

- `JobModels`、`JobController`
- `MatchModels`、`MatchController`
- `DeliveryModels`、`DeliveryController`
- `PluginModels`、`PluginController`
- `front/app/components/AuthProvider.tsx`

主要接口：

- `GET /api/jobs`：支持 platform/status/keyword/matchDecision/matchStatus/minScore/page/size/sort。
- `GET /api/jobs/{id}`；`GET /api/jobs/{id}/match`。
- `POST /api/jobs/{id}/analyze`，body `{force}`，必须带 `Idempotency-Key`。
- `POST /api/delivery/tasks`，body `{jobPostId, jobMatchId}`，必须带 `Idempotency-Key`。
- `GET /api/delivery/tasks`；`GET /api/delivery/tasks/{id}`。
- `PUT /api/delivery/tasks/{id}/greeting`，body `{version,greeting}`。
- `POST /api/delivery/tasks/{id}/confirm`，body `{version,acknowledged:true,assignedDeviceId}`，必须带 `Idempotency-Key`。
- `POST /api/delivery/tasks/{id}/skip`，body `{version,reason}`，必须带 `Idempotency-Key`。
- `POST /api/plugin/bind-code`，必须带 `Idempotency-Key`。
- `GET /api/plugin/devices`；`POST /api/plugin/devices/{id}/revoke`。

所有 Web API 必须经过 `secureRequest`，不得直接拼 Cookie/CSRF，不得把插件 Token 存入 Web 前端。

## 1. 共享类型与安全请求辅助

新增最小的 Cloud 前端类型/辅助模块，或在页面内清晰定义；避免把后端 DTO 重复成多份互相漂移的类型。

- 覆盖 Salary、JobSummary/JobDetail、MatchSummary/MatchView、Delivery Task 列表/详情、Device、BindCode 和分页结构。
- 状态/平台/推荐等级中文映射集中维护；未知值回退显示原值，不因新状态崩溃。
- 所有幂等写操作每次“新的用户动作”生成一个新的、非空且不超过 128 字符的 `Idempotency-Key`。同一次网络重试由现有请求层处理；不要在渲染期间生成或复用到另一种操作。
- 日期、薪资、空值统一安全格式化；保持 UTF-8 中文，不引入乱码。

## 2. 统一 `/jobs` 岗位池

修复现有页面文案并扩展为第五轮岗位入口：

- 保留关键词、平台、岗位状态、分页；增加匹配状态、推荐等级和最低分筛选。筛选变化回到第 1 页。
- 每行展示岗位、公司、薪资/地点/平台、AI 匹配状态、分数、推荐等级、投递任务状态和最近采集时间。
- 不逐行调用详情或 match API，不出现 N+1。
- 未分析或失败岗位提供单条“AI 分析/重新分析”动作，调用 analyze 后清晰展示排队状态；PENDING/PROCESSING 禁止重复点击。SUCCEEDED 进入详情查看完整分析。
- 后端匹配是异步的；前端不能用 `setInterval` 轮询。用户可手动刷新。
- 空状态、加载态、失败态和按钮忙碌态完整，失败信息使用请求层已脱敏的中文消息。

## 3. `/jobs/detail` 完整岗位分析

在现有详情页按实际 DTO 展示：

- 岗位基本信息、原岗位链接、描述、技能与福利。
- 匹配状态；成功时展示分数、推荐等级、岗位分析 summary、匹配优势 strengths、风险点 risks、AI 招呼语（仅作为建议预览）。
- 未分析时可以请求分析；FAILED 时展示稳定错误信息并允许 `force=true` 重新排队；PENDING/PROCESSING 仅允许刷新。
- `REVIEW + SUCCEEDED + BOSS/ZHILIAN + 当前没有活动投递任务` 时显示“加入投递清单”，调用 `POST /api/delivery/tasks` 并使用当前 match id。成功后更新详情并提供前往 `/delivery` 的入口。
- `APPLY` 说明“已由系统自动加入待确认清单”，以实际 `deliveryTask` 为准；`SKIP` 说明不会自动创建任务。若后端状态尚未同步，不伪造本地任务。
- 如果已有任务，展示任务状态和进入投递清单链接，不能创建重复任务。

## 4. 新增 `/delivery` 投递清单

新增统一投递页并加入侧边栏，页面至少包括：

### 列表与筛选

- 状态概览卡：待确认、已确认/执行中、需处理、已完成（按当前已加载/接口筛选结果清晰标注口径，不伪装为全库精确聚合）。
- 状态、平台、关键词筛选，分页和手动刷新。
- 每条展示岗位、公司、平台、分数、推荐等级、任务状态、设备、最近事件与更新时间。
- 点击条目后只加载当前任务详情，并按 job id 请求完整 match，用于展示岗位分析与风险点；不得为所有列表项请求完整 match。

### 逐条任务详情与动作

- 展示岗位分析、优势、风险、招呼语预览、状态、错误/暂停原因、尝试次数、设备和精简事件时间线。
- BOSS 招呼语：仅在后端允许的状态提供编辑；限制长度与服务端配置保持兼容（前端按 60 Unicode code point 给出计数/提示，服务端仍是最终校验）。保存后使用返回的 version/status，并提示修改已确认内容会重新要求确认。
- 智联：显示“智联投递不使用自定义招呼语”，输入框/编辑按钮禁用，不发送 greeting API。
- `PENDING_CONFIRMATION`、`PAUSED`、可重试 `FAILED` 可逐条确认。必须有明确确认勾选/对话框；可选一个当前 ACTIVE 且支持该平台的设备，或留空让任一兼容设备领取。
- `PENDING_CONFIRMATION / CONFIRMED / PAUSED / FAILED` 中后端允许的状态提供逐条“跳过”，带可选且不超过 200 字的原因。执行前明确二次确认，不做批量操作。
- `LEASED / EXECUTING` 只展示执行中，不提供会破坏租约的动作；`SUCCEEDED / SKIPPED / CANCELLED` 只读。
- 409 版本冲突时提示数据已变化并重新加载当前详情，不用旧 version 重试。

### 确认后显式唤醒

- confirm API 成功后，用返回的新状态/version 更新视图，然后调用下面第 6 节的 `sendCloudDeliveryWake(taskId)`，只唤醒这一条。
- 扩展返回 accepted 时显示“插件已接收”；未绑定/离线/忙碌/超时时显示“任务已确认，插件暂未接收”，保留 `CONFIRMED` 并提供“重新唤醒插件”。重新唤醒只发 Bridge 消息，不再调用 confirm。
- 订阅 `GET_JOBS_EXTENSION_EVENT`，只处理含合法 taskId 的 Cloud 投递事件；展示当前 task 进度。收到 succeeded/failed/paused/offline 等明确阶段时刷新当前任务/列表。组件卸载必须取消订阅。

## 5. 插件绑定与设备管理

在 `/delivery` 中增加折叠面板或独立卡片：

- 生成一次性绑定码并显示过期时间/剩余说明；不记录到 console/localStorage，不把绑定码放 URL。
- 列出设备名称、浏览器/扩展版本、能力（BOSS/ZHILIAN）、状态、最后在线和绑定时间。
- 允许撤销自己的设备并二次确认；撤销后刷新列表。不得显示或请求插件 Token。
- 没有 ACTIVE 兼容设备时仍允许“任一设备”确认，但清晰提示需要先绑定插件才能执行。

## 6. 扩展 `front/lib/chromeBridge.ts`

- 把 Cloud Web 开发源加入精确白名单：`localhost/127.0.0.1` 的 `6866` 与 `8080`；不得使用 `*`、任意端口或任意域名。
- 新增类型安全的 `sendCloudDeliveryWake(taskId)`：先严格校验 UUID，只发送 `source/type/taskId/requestId` 对应的必要负载；`type` 为 `CLOUD_DELIVERY_WAKE`。
- Cloud wake 结果只消费稳定 `success/accepted/taskId/state/code/message`，不展示 `rawMessage` 或未知运行时错误。
- 保持所有旧 Bridge 调用兼容，不破坏 Boss/智联旧页面。
- 事件订阅继续验证 `event.source === window`、精确 origin、扩展 source/type；为 Cloud 事件增加安全类型守卫，额外字段不进入任务操作。

## 7. 测试与验证

增加/更新 Vitest + Testing Library 测试，禁止真实网络和真实扩展：

1. `/jobs` 使用单次列表响应展示 score/decision/status，证明没有逐行 match 请求；筛选和 analyze 请求契约正确。
2. 岗位详情成功展示 summary/strengths/risks；只有 REVIEW 可手动创建，APPLY/SKIP 不错误创建。
3. `/delivery`：BOSS 可编辑、智联禁用；逐条 confirm/skip 使用当前 version 和幂等键；没有批量按钮。
4. confirm 成功但 Bridge 失败时任务仍显示 CONFIRMED，重试唤醒不会再次调用 confirm。
5. 扩展事件更新进度且卸载后取消监听。
6. 绑定码不进入 localStorage/URL；设备撤销后刷新。
7. `chromeBridge` 拒绝非白名单来源、非法 UUID/伪造响应，合法 wake 只发送必要字段并可在 6866/8080 工作。

必须运行并报告真实结果：

```powershell
Set-Location front
pnpm test
pnpm lint
pnpm build
```

在仓库根目录继续运行：

```powershell
git diff --check
node scripts/validate-repository-hygiene.mjs
```

不得通过删除旧测试、降低断言、加入静态字符串测试或关闭 lint/type check 来换取通过。

## 可验收标准

1. 用户从 `/jobs` 能看到 AI 匹配摘要，从详情能看到岗位分析、推荐等级、优势、风险点和招呼语。
2. APPLY 自动任务、REVIEW 手动加入、SKIP 不创建的界面行为与后端事实一致。
3. `/delivery` 完成待确认、逐条确认、跳过、BOSS 编辑话术、智联禁用话术、状态与事件查看。
4. 插件离线不会丢失已确认任务；重新唤醒不会重复 confirm，也不会出现批量/静默轮询。
5. 设备绑定码、插件 Token、Cookie/CSRF、简历全文和未知 runtime 错误均不泄露。
6. 旧页面与 Bridge 行为保持兼容，测试/lint/build 全部通过。

结束时逐项报告改动文件、测试命令和结果、剩余风险；不要执行 Git 操作。
