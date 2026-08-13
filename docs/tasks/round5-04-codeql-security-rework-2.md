# 第五轮 CodeQL SQL 注入告警返工任务

## 背景

上一轮已经把自由文本排序改成有限枚举，并将筛选值全部改为绑定参数；这些改动应保留。但 `JobRepository` 和 `DeliveryRepository` 仍通过 `StringBuilder`/字符串加法在运行时组合 WHERE、ORDER BY 片段，没有完全达到已批准任务“最终 SQL 是固定查询”的验收标准，也可能继续被 CodeQL 视为动态 SQL。

## 已确定方案

对两个列表查询使用完整、固定的命名参数 SQL 常量。筛选条件通过布尔开关参数控制，排序通过固定 SQL 内的 `CASE WHEN :sort = '...' THEN <固定列> END ASC/DESC` 实现。请求值只可作为 JDBC 绑定参数，不得参与 SQL 文本构造。

未启用的可空筛选参数也要绑定类型明确的安全占位值，避免 PostgreSQL 无法推断 NULL 参数类型。投递状态多选始终绑定非空集合：无筛选时使用不可能命中的占位状态，同时用布尔开关关闭条件，禁止产生 `IN ()`。

## 范围

1. `src/main/java/com/getjobs/cloud/jobs/JobRepository.java`
   - 删除列表/计数查询使用的动态 `StringBuilder` WHERE 组合、固定片段拼接和运行时 ORDER BY 拼接。
   - 定义完整固定的列表 SQL 与计数 SQL 常量；二者使用相同筛选语义。
   - 保留当前 `JobSort` 枚举与 `JobService` 的严格白名单解析。
   - 静态 SQL 必须支持现有 platform、status、keyword、capturedFrom、capturedTo、matchDecision、matchStatus、minScore，以及“只看每个岗位最新匹配记录”的原有语义。
   - 用命名参数绑定页码、筛选值、筛选开关和 sort 枚举名。

2. `src/main/java/com/getjobs/cloud/delivery/DeliveryRepository.java`
   - 删除 `TaskQuery.where()`、动态 WHERE 片段和运行时 ORDER BY 拼接。
   - 定义完整固定的列表 SQL 与计数 SQL 常量；保留现有联表、最新事件 LATERAL 查询和映射字段。
   - 保留当前 `TaskSort` 枚举与 `DeliveryService` 的严格白名单解析。
   - 静态 SQL 必须支持 statuses、platform、keyword、createdFrom、createdTo；空状态列表不得生成非法 SQL。
   - 用命名参数绑定页码、筛选值、筛选开关、状态集合和 sort 枚举名。

3. 按需调整或增加测试，验证空筛选、组合筛选、状态多选和全部合法排序仍正常，非法排序仍由现有服务层测试拒绝。

## 约束

- 不修改认证、简历加密、前端、CI、文档或数据库迁移；它们由 Codex 另行验收/补齐。
- 不改变 API 请求或响应结构、默认排序、分页、租户隔离和 RLS 语义。
- 最终传给 JDBC 的列表/计数 SQL 必须直接来自完整的源代码常量；不得包含 `StringBuilder`、`query.where()`、`" ORDER BY " + ...`、请求派生字符串或固定 SQL 片段的运行时拼接。
- SQL 的列名、方向、操作符和语法只能硬编码在固定常量中。
- 不执行任何 Git、推送、发布、部署、数据库迁移或破坏性操作。

## 验证

执行：

```powershell
.\gradlew.bat test --no-daemon --console=plain
```

如测试环境支持，再用仓库现有测试覆盖筛选和排序路径；不得为了通过测试而弱化断言。

## 验收标准

1. 两个仓库的列表/计数最终 SQL 均为完整固定常量，所有请求数据只走绑定参数。
2. 不再存在自由文本或请求派生字符串进入 SQL 文本的路径。
3. 现有筛选、排序、分页、最新匹配和最新投递事件语义不变。
4. 空 statuses 不会产生 `IN ()`，状态多选可工作。
5. `./gradlew test` 全部通过，无新增失败或跳过。
