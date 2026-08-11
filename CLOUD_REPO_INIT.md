# AI-JobPilot-Cloud 新仓库初始化指南

本文用于把 `AI-JobPilot` 本地稳定版安全地复制为独立的 `AI-JobPilot-Cloud` 仓库。目标是保留经过验证的工程基线和可迁移业务能力，同时不复制本地运行数据、招聘平台会话、密钥、真实简历和原仓库 Git 历史。

> 本文是操作方案，不代表本轮已经再次复制或创建仓库。所有命令都应在 `AI-JobPilot` 本地稳定版完成冻结并选定基线后执行。

## 1. 两个仓库的定位

| 仓库 | 定位 | 发布节奏 | 数据与执行边界 |
| --- | --- | --- | --- |
| `AI-JobPilot` | Windows 本地稳定版 | 以稳定性、兼容性和本地体验为主 | 单机 SQLite、本地配置、Chrome Bridge/Playwright |
| `AI-JobPilot-Cloud` | “投递牛马”SaaS 云端版 | 按 SaaS 路线独立开发和发布 | 云端保存用户业务数据；采集和投递在用户浏览器插件中执行 |

两个仓库相互独立，不把 Cloud 当成本地版的长期分支，也不让 Cloud 的 PostgreSQL、多用户或鉴权改造反向影响本地稳定版。通用缺陷修复可以经过评审后选择性移植，但不能自动双向同步整批提交。

## 2. 复制前检查本地稳定版

在 `AI-JobPilot` 源仓库根目录打开 PowerShell，先执行：

```powershell
git status --short --branch
git branch --show-current
git remote -v
git log -5 --oneline --decorate
git tag --sort=-creatordate | Select-Object -First 10
```

确认以下条件后再继续：

- 当前基线来自预期的稳定分支或 Release Tag，不是临时实验提交。
- `git status --short` 没有未提交修改；如有修改，先由原作者决定提交、暂存或保留，不要直接复制混入 Cloud。
- 已记录基线提交：`git rev-parse HEAD`，建议后续写入 Cloud 仓库的 `BASELINE.md` 或初始化 PR。
- `origin` 指向本地版仓库，避免把 Cloud 代码误推到本地版远端。
- 当前版本的测试、前端构建和插件校验能够通过。

建议在复制前运行当前仓库已有检查：

```powershell
.\gradlew.bat test
Push-Location front
pnpm install --frozen-lockfile
pnpm lint
pnpm build
Pop-Location
node scripts/validate-chrome-extension.mjs
```

成功标志是所有命令退出码为 `0`，且没有测试失败、构建错误或扩展清单错误。若源仓库本身有失败项，应记录为基线已知问题，不能在不知情的情况下带入新仓库。

### 冻结建议

1. 为本地稳定版打有注释的 Tag，例如 `local-v1.3.0-cloud-baseline`。
2. 发布或归档该 Tag 对应的稳定包。
3. 记录基线提交、测试结果、发布日期和已知问题。
4. 约定本地版仅接收本地兼容性和严重缺陷修复；Cloud 需求在新仓库实现。

## 3. 禁止复制的内容

以下内容不得进入新仓库，也不得先复制后再依赖 `.gitignore` 补救：

| 内容 | 原因 | Cloud 处理方式 |
| --- | --- | --- |
| `.env`、`.env.*` 实际配置 | 可能含数据库密码、AI Key、服务密钥 | 仅保留无真实值的 `.env.example` |
| `db/` | SQLite 数据和本地个人信息 | Cloud 新建 PostgreSQL 空库和 Flyway 迁移 |
| `data/` | 本地运行数据 | 仅在运行时挂载私有卷，不入库 |
| `logs/`、`target/logs/` | 日志可能含岗位、路径或隐私 | 由云端日志系统收集并脱敏 |
| `output/` | 导出结果可能含个人数据 | 使用私有对象存储或受控下载 |
| `target/`、`build/`、`.gradle/` | Java/Gradle 构建产物与缓存 | 由 CI 重新构建 |
| `front/.next/` | Next.js 构建产物 | 由 CI 重新构建 |
| 任意 `node_modules/` | 包缓存体积大且不可审计 | 根据锁文件重新安装 |
| `chrome-profile/`、浏览器用户目录 | 含 Cookie、LocalStorage、登录会话 | 永不上传，插件只使用用户本机浏览器会话 |
| Cookie、Token、API Key、账号密码 | 高风险凭证 | 使用占位配置；运行密钥进入密钥管理系统 |
| 真实简历、身份证明、个人截图、导出 CSV | 高敏感个人信息 | 只保留虚构且明确标注的演示数据 |

还应排除：IDE 缓存、临时文件、堆转储、浏览器缓存、压缩备份、数据库备份、私钥、证书私钥和带真实内容的测试夹具。

### `.gitignore` 最低要求

Cloud 仓库首次 `git add` 之前，至少确认以下规则存在：

```gitignore
.env
.env.*
!.env.example
db/
data/
logs/
output/
target/
build/
.gradle/
front/.next/
**/node_modules/
chrome-profile/
*.sqlite
*.sqlite3
*.db
*.log
*.pem
*.key
```

## 4. 推荐复制方式：只导出已跟踪文件

不要直接复制整个工作目录。推荐用 `git archive` 从已确认的提交导出已跟踪文件，因为它不会包含 `.git`、未跟踪的 `.env`、数据库和本地缓存。

在本地版仓库根目录执行，先把 `<稳定版提交或Tag>` 替换为已确认的值：

```powershell
$CloudBaseline = '<稳定版提交或Tag>'
git archive --format=zip --output ..\AI-JobPilot-Cloud-bootstrap.zip $CloudBaseline
New-Item -ItemType Directory -Path ..\AI-JobPilot-Cloud -ErrorAction Stop
Expand-Archive -LiteralPath ..\AI-JobPilot-Cloud-bootstrap.zip -DestinationPath ..\AI-JobPilot-Cloud
```

成功标志：相邻目录 `AI-JobPilot-Cloud` 已创建，内部没有 `.git`，也没有源工作区的未跟踪文件。

即使使用 `git archive`，仍需检查“原本被错误跟踪的敏感文件”。进入新目录后执行：

```powershell
Set-Location ..\AI-JobPilot-Cloud
Get-ChildItem -Force
Get-ChildItem -Recurse -Force -File -Include .env,*.db,*.sqlite,*.sqlite3,*.pem,*.key,*.pfx,*.log
rg -l -i "cookie|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret" . -g '!*.png' -g '!*.jpg' -g '!*.ico'
```

`rg -l` 只列文件名，不把疑似密钥内容打印到终端。匹配结果不等于一定泄密，但每个命中文件都要人工确认；演示数据必须完全虚构。

## 5. 新仓库初始化步骤

### 5.1 建立独立 Git 历史

在新的 `AI-JobPilot-Cloud` 目录执行：

```powershell
git init -b main
git status --short --branch
```

先完成以下文档与配置调整，再首次提交：

1. 把 README 改成 SaaS 云端版定位，不继续宣称是纯本地单机产品。
2. 写明本地版来源仓库和基线提交，但不要复制原 `.git` 历史。
3. 校验 `.gitignore`、`.dockerignore`、`.env.example` 中没有真实值。
4. 保留许可证和第三方声明；如许可证或品牌策略要变化，应单独审核。
5. 先删除或替换真实个人资料、历史截图和带账号信息的文档。
6. 暂时保留可评估的源代码，但用迁移清单标注“复用、重构、删除”，不要把本地架构误当成 SaaS 已完成。

首次提交建议：

```powershell
git add .
git diff --cached --stat
git diff --cached --check
git commit -m "初始化：创建投递牛马 SaaS 云端版仓库"
```

提交前必须再次确认暂存区只有预期基线，不包含敏感文件。

### 5.2 创建 GitHub 独立仓库

先在 GitHub 确认 `AI-JobPilot-Cloud` 尚不存在，并决定公开或私有。使用 GitHub CLI 时，可按实际可见性二选一：

```powershell
gh repo create AI-JobPilot-Cloud --private --source . --remote origin --push
```

如果已在网页创建空仓库，则执行：

```powershell
git remote add origin https://github.com/<你的账号>/AI-JobPilot-Cloud.git
git push -u origin main
```

成功标志：`git remote -v` 只指向 Cloud 仓库，GitHub 默认分支为 `main`，本地版仓库没有被改写。

### 5.3 建立开发保护规则

- `main` 只保留可部署基线，功能通过分支和 PR 进入。
- 启用 CI、CodeQL、依赖更新和秘密扫描。
- 为 `main` 配置必需检查和至少一次评审；不要在初始化脚本中自动合并。
- 生产密钥使用 GitHub Environments/部署平台密钥管理，不写入仓库变量文件。
- Cloud 与本地版分别维护版本号、Release、Issue 和路线图。

## 6. README 初始定位

README 首页应让新成员在一分钟内理解：

1. 这是“投递牛马 SaaS 云端版”，不是本地版的覆盖更新。
2. 云端负责用户、简历、求职目标、岗位、AI 匹配、投递清单、插件任务、额度和后台。
3. 插件在用户自己的 Chrome/Edge 中采集并执行用户确认过的任务。
4. 云端不保存招聘平台账号、密码或 Cookie，不直接登录招聘平台。
5. 当前开发阶段、已完成能力和明确的非目标。
6. 本地启动依赖、测试命令、架构和安全文档入口。
7. 本地版仓库链接、Cloud 基线提交来源和两者的维护关系。

初始状态必须如实写“云端能力尚未完成”，不能仅因代码可以本地启动就宣称可用于生产 SaaS。

## 7. 当前代码迁移分级

| 分类 | 当前模块 | Cloud 处理 |
| --- | --- | --- |
| 优先复用并重构 | `SalaryParser`、岗位 DTO、AI 分析解析、投递状态语义、Boss/智联插件采集与投递脚本、前端 UI 组件、CI/CodeQL | 补 `user_id`、幂等、鉴权、错误模型和自动化测试 |
| 仅作参考 | MyBatis Mapper、SQLite Flyway、平台 Controller、SSE、本地启动脚本、Docker | 按 PostgreSQL、Redis、Web 会话和云部署方式重建 |
| 不进入云端服务 | `CookieService`/`cookie` 表、服务端 Playwright 登录投递、`chrome-profile`、本地浏览器控制接口 | 保留在本地版；Cloud 插件只使用用户本机会话 |

## 8. 初始化完成检查表

- [ ] 源稳定版工作区干净，并记录基线提交或 Tag。
- [ ] 通过 `git archive` 或等价的“只复制已跟踪文件”方式创建目录。
- [ ] 禁止复制清单中的目录和文件均不存在。
- [ ] README、仓库名、远端地址均明确指向 Cloud。
- [ ] `.gitignore`、`.dockerignore` 和秘密扫描已检查。
- [ ] 本地测试、前端构建和插件校验结果已记录。
- [ ] 新仓库使用独立 Git 历史，未覆盖本地版远端。
- [ ] `main` 保护、CI、CodeQL 和安全报告渠道已设置。
- [ ] 未自动合并 PR、删除分支或执行 force push。
