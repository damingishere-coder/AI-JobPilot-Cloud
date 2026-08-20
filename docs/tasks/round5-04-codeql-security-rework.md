# 第五轮安全返工：修复 PR #7 的 4 条 CodeQL 高危告警

## 角色与背景

你是 Claude Code CLI + DeepSeek 受限执行者。Codex 是主控，已完成第五轮功能和本地全量验证，并创建草稿 PR #7。当前分支为 `feature/ai-matching-delivery`，工作区在执行本任务前应只有本任务文件这一项未提交修改。

本轮 CodeQL 的 Java/JavaScript 分析器本身均运行成功，但 PR 差异门槛报告 4 条 Java 高危告警：

1. `java/csrf-unprotected-request-type`：`src/main/java/com/getjobs/cloud/auth/AuthController.java` 当前 `GET /api/me` 会经过可能访问/更新会话状态的认证链路，CodeQL 认为 GET 不受默认 CSRF 保护。
2. `java/sql-injection`：`src/main/java/com/getjobs/cloud/jobs/JobRepository.java` 列表 SQL 拼接动态 WHERE/ORDER BY。
3. `java/sql-injection`：`src/main/java/com/getjobs/cloud/delivery/DeliveryRepository.java` 列表 SQL 拼接动态 WHERE/ORDER BY。
4. `java/uncontrolled-arithmetic`：`src/main/java/com/getjobs/cloud/resume/ResumeService.java:pack` 直接把两个数组长度相加用于分配。

这些告警链接分别是 Code Scanning #74-#77。不得用 CodeQL 抑制注释、排除测试/路径、降低查询集、关闭工作流或把告警标记为误报来换取通过。

## 已批准方案

### 1. `/api/me` 改为显式受 CSRF 保护的 POST

- 把 Cloud Web 会话自检从 `GET /api/me` 改为 `POST /api/me`，响应 DTO 和业务语义不变。
- `front/app/components/AuthProvider.tsx` 的 `refresh` 必须先通过现有 `fetchCsrf()` 取得 CSRF，再用 `POST /api/me` 和该 Token 请求当前用户。
- 保持登录、注册、登出路径与响应兼容；匿名登出继续允许且不创建不必要状态。
- 更新所有相关前端测试、Java 集成测试和测试客户端调用。
- 增加/保留明确断言：已登录用户缺少/错误 CSRF 调用 `POST /api/me` 返回 `403 CSRF_INVALID`，合法 Token 成功；不存在 `GET /api/me` 的状态修改旁路。
- 不要通过手写 Controller 内 Token 比较替代 Spring Security 的 `CsrfFilter`。

### 2. 岗位列表 SQL 完全由静态 SQL 片段组成

- 不得把用户 `sort`、筛选值或从它们派生的字符串直接拼进最终 SQL。
- 把排序解析为强类型的有限枚举/值对象（列 + 方向），Repository 只能用 `switch` 从预声明的常量排序语句中选择；禁止返回/传递自由 `String orderBy`。
- 把动态 WHERE 改为一条静态参数化查询，或若为性能必须分支，则每条分支都是源代码中的完整固定 SQL 常量。所有筛选值继续使用绑定参数。
- 保持现有查询能力、默认排序、分页、最新 match 筛选语义和结果稳定次序。
- 非法 sort 仍返回现有 `400 VALIDATION_ERROR`，并增加合法排序/恶意排序测试。

### 3. 投递列表 SQL 完全由静态 SQL 片段组成

- 与岗位列表相同：排序使用强类型有限枚举/值对象，Repository 只选择固定常量，不接收自由排序 SQL 字符串。
- 状态多选不得用用户数量/内容拼接 `?` 字符串。优先使用 `NamedParameterJdbcTemplate` 的集合参数扩展，并保证传给模板的 SQL 文本本身是静态常量；空集合应有明确语义且不能生成非法 `IN ()`。
- 平台、关键词、日期、分页全部用绑定参数；保持当前 RLS、当前用户、最近事件、设备和 match JOIN 语义。
- 保持合法多状态筛选、默认排序、分页与非法 sort 的行为；增加回归测试。

### 4. 简历密文打包做显式上限并移除不受控加法分配

- 明文文件上限仍为 `ResumeFileValidator.MAX_BYTES`（10 MiB），不得提高。
- 在打包前验证：nonce 长度符合 AES-GCM 当前契约，ciphertext 非空且不超过由明文上限和 GCM 固定开销推导的常量上限。
- 不要直接用 `nonce.length + ciphertext.length` 作为数组或 ByteBuffer 分配大小。可以使用固定上限的有界缓冲/输出流，并在写入前完成每个数组的独立边界校验。
- 异常使用稳定、非敏感的服务端错误，不记录密文/明文。
- 增加单元测试覆盖正常打包、畸形 nonce、过大 ciphertext；不得分配攻击者指定大小的数组。

## 范围与限制

- 只修改上述认证、岗位列表、投递列表、简历打包相关 Java/前端和测试，以及本任务文件。
- 不修改数据库迁移、Chrome 扩展执行逻辑、AI 匹配产品规则、投递状态机、依赖版本、GitHub 工作流或 CodeQL 配置。
- 不执行 Git commit/push/PR，不发布、不部署、不迁移生产数据。
- 保留全部现有提交和用户修改，不重写历史。
- 不泄露账号、Token、Cookie、CSRF、简历全文、密钥或原始异常。

## 必须验证

至少运行并真实报告：

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
Set-Location front
pnpm test
pnpm lint
pnpm build
Set-Location ..
git diff --check
node scripts/validate-repository-hygiene.mjs
```

如果完整测试耗时，仍必须完成，不得只报告编译通过。结束时结构化报告：改动文件、每条告警的消除方式、测试结果、剩余风险。不要执行 Git 操作。

## 验收标准

1. `/api/me` 只有受 CSRF 保护的状态访问方式，合法前端启动/刷新仍工作。
2. 两个列表 Repository 的最终 SQL 不含任何来自请求或请求派生自由字符串的拼接，筛选/排序/分页行为不退化。
3. 简历密文打包不会因不受控长度加法导致整数溢出或超大分配。
4. 现有 222 个 Java 测试基线不减少，并新增针对性回归测试；前端 45 个测试基线不减少。
5. 不使用任何扫描抑制或扩大范围的依赖/架构重写。
