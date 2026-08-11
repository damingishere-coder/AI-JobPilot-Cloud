# AI-JobPilot-Cloud 代码迁移清单

本文记录从本地版带入 Cloud 仓库的代码如何处理。分类描述的是目标状态，不表示本轮立即删除或完成重构。

## 基线记录

| 项目 | 记录 |
| --- | --- |
| 来源仓库 | [`damingishere-coder/AI-JobPilot`](https://github.com/damingishere-coder/AI-JobPilot) |
| 来源提交 | [`3de82dc24aea3f1d02b380dae68b4e72352ee753`](https://github.com/damingishere-coder/AI-JobPilot/commit/3de82dc24aea3f1d02b380dae68b4e72352ee753) |
| 来源版本 | `1.3.0` 多平台采集与分析增强版 |
| Cloud 首次提交 | `61446dd`（独立 Git 历史） |
| 当前 Java 命名空间 | `com.getjobs`，初始化阶段保留 |

来源提交中的后端构建、前端包、扩展清单和 Release 工作流与 Cloud 初始基线的对应文件具有相同 Git Blob，可用于追溯复制来源。

## 直接复用并重构

| 能力 | 当前代码 | Cloud 处理 |
| --- | --- | --- |
| 工资解析与岗位标准化 | `SalaryParser`、岗位 DTO、平台枚举与适配器接口 | 保留纯业务逻辑，补充租户上下文、统一错误和边界测试 |
| AI 分析结果处理 | AI 分析服务中的解析、状态和评分语义 | 拆出与本地配置、SQLite Mapper 无关的领域逻辑 |
| 投递状态语义 | `DeliveryStatus`、确认与结果 DTO | 复用状态含义，按 Cloud 状态机补幂等和审计字段 |
| 浏览器采集与投递 | Boss、智联扩展脚本及测试 | 保留在用户浏览器执行，改为绑定 Cloud 任务并回传结果 |
| 前端通用组件 | UI 组件、筛选、工资展示和统计组件 | 在加入登录、租户和新 API 后继续复用 |
| 工程检查 | 后端测试、前端构建、扩展校验、CI 与 CodeQL | 作为后续阶段的最低质量门槛持续维护 |

## 仅作重构参考

| 能力 | 原因 | Cloud 目标 |
| --- | --- | --- |
| MyBatis Mapper、实体和 SQLite Flyway | 当前表结构按单机单用户设计 | 使用 PostgreSQL、`user_id` 隔离和 Cloud 专用 Flyway 空基线重建 |
| 平台 Controller 与 SSE | 当前接口没有登录、租户、统一错误和幂等约束 | 按 Cloud API 设计重新实现鉴权、分页、任务和错误模型 |
| AI 配置与本地环境配置 | 当前配置可保存到单机数据库或本地环境 | 改为用户级配置、服务端 Secret 和受控模型凭证 |
| Docker 与启动脚本 | 当前只编排本地前后端和 SQLite | 后续加入 Web、API、Worker、PostgreSQL、Redis 与统一入口 |
| 首页与平台工作台 | 当前流程围绕本机采集、分析和投递 | 按 Cloud 账号、资料、岗位池、投递清单重新组织信息架构 |

## 不进入 Cloud 服务

以下代码当前仍保留，用于保证迁移基线可构建和辅助评估；在对应 Cloud 替代能力完成并通过测试后，再用独立 PR 删除：

- `CookieService`、Cookie Controller/Mapper/Entity、Cookie 表和保存 Cookie 接口。
- 服务端 Playwright 登录、投递 Worker、`PlaywrightManager` 和浏览器点击/拖动控制接口。
- `chrome-profile`、浏览器可执行文件路径、本地用户目录和自动打开浏览器逻辑。
- 依赖服务器持有招聘平台会话、绕过验证或无人确认批量投递的任何实现。

Cloud 服务器不得保存或处理招聘平台密码、Cookie 和登录 Token。最终投递只能由用户浏览器插件在用户确认后执行。

## 迁移规则

1. 本轮不移动 `com.getjobs` 包、不改数据库结构、不删除上述源码。
2. 每个后续阶段先建立 Cloud 接口与测试，再迁移纯业务逻辑，最后删除已被替代的本地实现。
3. 涉及多用户数据的表、查询、缓存、队列和文件必须带明确租户边界，并覆盖 A/B 用户隔离测试。
4. 旧模块退出前必须确认没有 API、前端、扩展、测试或构建脚本继续引用。
5. 删除 Cookie、浏览器 Profile、数据库或历史数据属于高风险操作，必须在独立任务中说明影响和回滚方式。
