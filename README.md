# AI-JobPilot-Cloud｜投递牛马 SaaS 云端版

AI-JobPilot-Cloud 是“投递牛马”的 SaaS 云端版仓库，目标是把求职资料管理、岗位汇总、AI 匹配和投递清单放到云端，同时由用户自己的浏览器插件完成招聘平台页面上的采集与投递操作。

> 当前状态：仓库刚从 AI-JobPilot 本地稳定版建立开发基线，云端多用户能力尚在开发中，暂不应作为生产级 SaaS 直接部署。

## 与本地版的关系

- [AI-JobPilot](https://github.com/damingishere-coder/AI-JobPilot) 继续作为本地单机版独立维护。
- 本仓库是新的 [AI-JobPilot-Cloud](https://github.com/damingishere-coder/AI-JobPilot-Cloud)，后续面向云端后台与浏览器插件执行器演进。
- 两个仓库相互独立；云端版不会覆盖或替换用户电脑上的本地版仓库。

## 目标架构

### 云端后台

云端后台负责用户系统、简历管理、求职目标、岗位池、AI 匹配、投递清单、额度和付费等 SaaS 能力。

### 浏览器插件执行器

浏览器插件运行在用户自己的浏览器中，复用用户已经登录的招聘平台页面，用于采集岗位并执行用户明确确认过的投递任务。

```text
云端后台：资料、岗位、匹配、清单、额度
                 ↕ 受控任务与结果
用户浏览器插件：页面采集、用户确认、本地执行
                 ↕
招聘平台：用户自己的登录会话
```

## 安全边界

- 服务器不保存招聘平台账号、密码、Cookie 或登录令牌。
- 服务器不代替用户登录招聘平台，也不绕过验证码、风控或平台限制。
- 投递动作必须由用户在浏览器端确认，再由浏览器插件在用户设备上执行。
- 不得向仓库提交 API Key、Token、Cookie、真实 `.env`、数据库、日志、真实简历或浏览器缓存。
- `.env.example` 只用于展示配置项，所有密钥字段必须保持为空或使用明确的占位值。

如发现安全问题，请按照 [SECURITY.md](SECURITY.md) 中的方式处理，不要在公开 Issue 中披露敏感信息。

## 开发路线

云端版将依次建设用户与求职资料、岗位与 AI 匹配、浏览器插件闭环以及额度与付费系统。详细阶段和完成标准见 [CLOUD_ROADMAP.md](CLOUD_ROADMAP.md)。

## 当前基线的本地开发

当前基线仍保留原本地版的 Java 21 后端、Next.js 前端和 Chrome 插件代码，便于逐步迁移与重构。

环境要求：

- Java 21
- Node.js 20.19 或更高版本
- pnpm 10.20.0
- Chrome
- Docker Desktop（可选）

后端：

```powershell
.\gradlew.bat bootRun
```

前端：

```powershell
cd front
pnpm install --frozen-lockfile
pnpm dev
```

常用验证：

```powershell
.\gradlew.bat test
.\gradlew.bat build

cd front
pnpm lint
pnpm build
```

Chrome 插件开发说明和现有本地版架构资料暂时保留在 `chrome-extension/`、`docs/` 和 `doc/` 中；隐私敏感的历史截图和个人经历文档未迁移。这些内容代表迁移基线，不代表云端路线已经完成。

## 许可证

详见 [LICENSE](LICENSE)。
