# AI-JobPilot-Cloud｜投递牛马 SaaS 云端版

AI-JobPilot-Cloud 是“投递牛马”的独立 SaaS 云端版仓库。目标是把账号、求职资料、岗位池、AI 匹配、投递清单和额度管理放到云端，同时由用户自己的浏览器插件完成招聘平台页面上的采集，以及用户明确确认后的投递操作。

> **当前状态：第 5 轮统一 AI 岗位页与投递清单 Web 界面已完成。** Cloud 模式已经具备用户系统、简历安全上传与异步文本提取、版本化求职目标、带 AI 匹配摘要与推荐等级的岗位池、岗位详情完整分析、逐条确认的投递清单（BOSS 招呼语编辑、智联禁用话术、确认后显式唤醒插件）、插件绑定与设备管理，以及对应的 RLS 用户隔离；真实岗位采集、额度和生产部署仍未完成，请勿直接用于生产 SaaS。

## 当前进度

- Cloud 仓库已经使用独立 Git 历史，`origin` 只指向 [AI-JobPilot-Cloud](https://github.com/damingishere-coder/AI-JobPilot-Cloud)。
- 代码来源基线为 [AI-JobPilot@3de82dc](https://github.com/damingishere-coder/AI-JobPilot/commit/3de82dc24aea3f1d02b380dae68b4e72352ee753)，Cloud 首次初始化提交为 `61446dd`。
- Java 21 后端、Next.js 前端、Chrome 扩展和旧 SQLite 源码继续保留，供后续按模块迁移和重构。
- Cloud Docker 环境已包含 Nginx、Web、API、AI Worker、一次性 Flyway 迁移、PostgreSQL 16、Redis 7 和 ClamAV；默认只有 `127.0.0.1:8080` 对宿主机开放。
- Cloud 进程使用隔离入口，不加载旧 Cookie、Playwright、SQLite 初始化和旧 Controller；认证只在 Cloud API/Web 开启，Windows 旧本地入口继续按原方式运行。
- Web 鉴权统一使用 Redis Session，不使用 JWT；PostgreSQL 用户资料、简历、求职目标和岗位通过事务级用户上下文和 RLS 隔离。
- 简历支持 PDF、DOCX、TXT，上传前先执行 ClamAV 扫描，原文件与提取文本使用 AES-256-GCM 加密；岗位池支持 AI 匹配摘要筛选，详情页展示完整岗位分析，投递清单支持逐条确认、跳过与招呼语编辑，插件绑定与设备管理在投递清单页内完成。

详细阶段和完成标准见 [Cloud 路线图](CLOUD_ROADMAP.md)，代码保留与退出边界见 [迁移清单](CLOUD_MIGRATION_INVENTORY.md)。

## 产品与安全边界

| 位置 | 负责内容 | 明确不做 |
| --- | --- | --- |
| Cloud 后台 | 用户、简历、求职目标、岗位、AI 匹配、投递清单、额度和后台管理 | 不保存招聘平台账号、密码、Cookie 或登录令牌 |
| 用户浏览器插件 | 使用用户已登录的招聘平台标签页采集岗位，执行用户确认过的投递任务 | 不绕过验证码、风控、访问频率或平台限制 |
| 招聘平台 | 保持用户自己的登录会话和平台交互 | Cloud 服务器不代替用户登录 |

仓库禁止提交真实 `.env`、API Key、Token、Cookie、数据库、日志、浏览器 Profile、真实简历、账号截图或私钥。`.env.example` 只展示配置项，敏感字段必须为空。完整规则见 [Cloud 安全与隐私原则](CLOUD_SECURITY.md)。

## 当前代码基线

```text
src/                 Java 21 / Spring Boot 后端迁移基线
front/               Next.js 16 前端迁移基线
chrome-extension/    用户浏览器执行器迁移基线
deploy/              Nginx HTTP/HTTPS 配置示例
docker/              PostgreSQL、Redis 容器初始化脚本
scripts/             备份恢复、扩展与仓库卫生校验
```

Cookie 保存、服务端 Playwright 登录投递等本地能力仍留在源码中供迁移评估，但 Cloud Boot Jar 从 `com.getjobs.cloud.CloudApplication` 启动，不扫描这些旧组件。

## 开发与验证

推荐只安装 Docker Desktop，然后在项目根目录一键启动完整 Cloud 环境：

```powershell
.\start_docker.ps1
```

启动成功后访问 `http://localhost:8080`。完整说明、探针和备份恢复命令见 [Cloud Docker 一键启动指南](README_DOCKER.md)。

如需运行保留的旧本地开发入口，环境要求为 Java 21、Node.js 20.19 或更高版本、pnpm 10.20.0 和 Chrome：

启动当前开发基线：

```powershell
# 后端，默认 http://localhost:8888
.\gradlew.bat bootRun

# 前端，另开一个终端，默认 http://localhost:6866
cd front
$env:CLOUD_LOGIN_REQUIRED = "false"
pnpm install --frozen-lockfile
pnpm dev
```

提交前验证：

```powershell
# 仓库数据与敏感配置边界
node scripts/validate-repository-hygiene.mjs

# 后端
.\gradlew.bat test --no-daemon

# 前端
cd front
pnpm lint
pnpm test
pnpm build
cd ..

# Chrome 扩展与 Docker 配置
node scripts/validate-chrome-extension.mjs
docker compose config --quiet
```

所有命令退出码为 `0` 即通过。前端当前有 36 条来自迁移基线的 lint warning，但没有 lint error；后续应逐步消化，不能新增错误。

## Cloud 文档

| 文档 | 内容 |
| --- | --- |
| [总体架构](CLOUD_ARCHITECTURE.md) | 模块边界、数据流、部署拓扑与插件职责 |
| [API 设计](CLOUD_API_DESIGN.md) | 第一版接口、鉴权、幂等与错误模型 |
| [数据库设计](CLOUD_DATABASE_DESIGN.md) | PostgreSQL 目标模型、隔离与迁移原则 |
| [安全与隐私](CLOUD_SECURITY.md) | 数据分类、凭证边界、日志与插件安全 |
| [开发路线图](CLOUD_ROADMAP.md) | 阶段 0-9、验收标准与跨阶段门槛 |
| [仓库初始化指南](CLOUD_REPO_INIT.md) | 独立仓库来源、复制方式和安全检查 |
| [代码迁移清单](CLOUD_MIGRATION_INVENTORY.md) | 复用、重构和退出 Cloud 的模块清单 |
| [云端基础设施](CLOUD_INFRASTRUCTURE.md) | 第 2 轮拓扑、Profile、迁移、存储、健康与备份边界 |
| [用户系统与隔离](CLOUD_USER_SYSTEM.md) | 第 3 轮注册登录、Redis Session、CSRF、锁定、审计与 RLS |
| [简历、偏好与岗位池](CLOUD_RESUME_PREFERENCES_JOB_POOL.md) | 第 4 轮上传安检、加密、异步解析、求职目标版本和只读岗位池 |
| [Docker 启动指南](README_DOCKER.md) | 一键启动、探针、常用运维和故障排查 |
| [参与贡献](CONTRIBUTING.md) | 分支、提交、测试、安全和 PR 要求 |

原本地版的 Windows、Docker、任务流和历史 POC 文档仍保留在仓库中作为迁移参考，但不再作为 Cloud 的使用入口。

## 与本地版的关系

- [AI-JobPilot](https://github.com/damingishere-coder/AI-JobPilot) 继续作为本地单机稳定版独立维护。
- Cloud 与本地版分别维护分支、Issue、PR、版本和发布流程。
- 通用缺陷修复经评审后选择性移植，不自动双向同步整批提交。

## 许可证

详见 [TOUDI NIUMA Non-Commercial License 1.0](LICENSE)。
