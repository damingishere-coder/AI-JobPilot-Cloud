# AI-JobPilot-Cloud 文档中心

这里仅汇总“投递牛马 SaaS 云端版”的当前有效文档。项目目前处于开发迁移阶段，文档中的目标设计不代表对应能力已经上线。

## Cloud 设计与实施

| 文档 | 内容 |
| --- | --- |
| [项目主页](../README.md) | 当前状态、产品边界、开发验证和文档导航 |
| [总体架构](../CLOUD_ARCHITECTURE.md) | Cloud 模块、数据流、部署拓扑和浏览器插件边界 |
| [API 设计](../CLOUD_API_DESIGN.md) | 第一版 API、鉴权、幂等、分页与错误模型 |
| [数据库设计](../CLOUD_DATABASE_DESIGN.md) | PostgreSQL 目标模型、多用户隔离与迁移原则 |
| [安全与隐私](../CLOUD_SECURITY.md) | 凭证、简历、日志、文件、插件和运维安全 |
| [开发路线图](../CLOUD_ROADMAP.md) | 阶段 0-9、验收标准和发布门槛 |
| [仓库初始化指南](../CLOUD_REPO_INIT.md) | 独立仓库来源、安全复制和初始化检查 |
| [代码迁移清单](../CLOUD_MIGRATION_INVENTORY.md) | 直接复用、重构参考和退出 Cloud 的模块 |
| [参与贡献](../CONTRIBUTING.md) | 分支、提交、测试、安全和 PR 要求 |

## 自动化检查

| 检查 | 作用 |
| --- | --- |
| `Repository Hygiene` | 拦截本地数据库、日志、浏览器 Profile、真实环境配置、私钥和已填充示例密钥 |
| `CI` | 后端测试与构建、前端 lint/build、Chrome 扩展和 Docker 配置校验 |
| `CodeQL` | Java/Kotlin 与 JavaScript/TypeScript 安全分析 |
| `Release` | 构建 `AI-JobPilot-Cloud-*` 预览或标签发布产物并生成校验和 |
| `Dependabot` | 定期检查后端、前端和 GitHub Actions 依赖更新 |

## 历史文档说明

仓库根目录的旧 Windows、Docker、任务流、架构、路线图、英文 README，以及 `doc/` 下的历史方案仍作为迁移参考保留。本页不再链接这些本地单机版入口，避免把旧运行方式误认为 Cloud 已完成能力。

新增或修改文档时，应优先更新 Cloud 系列文档；不要在文档、截图或示例中提交真实 Cookie、API Key、账号、简历、数据库或本机路径。
