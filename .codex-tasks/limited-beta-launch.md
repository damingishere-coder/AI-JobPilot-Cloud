# 小范围上线执行任务

## 背景

AI-JobPilot Cloud 需要从仅本人预发布验证推进到 `toudiniuma.cn` 上的 6–10 人邀请测试。首期只支持 BOSS 直聘，Chrome 扩展继续使用手动 ZIP 安装，任何真实投递都必须由当前用户逐项确认。

## 目标

在不触碰生产环境、真实账号和付费资源的前提下，完成可审查的上线代码与运维准备：邀请注册、邮箱验证与密码重置、分版本授权记录、账号永久删除、DeepSeek 脱敏配置、正式域名与扩展配置、COS 加密备份/恢复工具、CI 与上线文档。

## 允许修改范围

- `src/main/java/com/getjobs/cloud/**`、`src/main/resources/**`、对应 `src/test/**`
- `front/app/**`、`front/lib/**` 及对应测试
- `chrome-extension/**`、`scripts/**`、`deploy/**`
- `.env.example`、`docker-compose.yml`、`.github/workflows/**`
- 本任务直接相关的上线、备份、隐私与 API 文档

## 禁止修改范围

- 不读取、修改或提交 `.secrets/`、`.env`、Cookie、Token、API Key、证书私钥或浏览器数据
- 不访问或修改生产服务器、DNS、COS、邮件、DeepSeek、招聘平台账号或真实用户数据
- 不开启真实投递，不绕过验证码、登录或风控
- 不自动合并最终 PR，不强推、不改写历史、不删除数据
- 不写正式法律条款；只提供待律师文本的安全集成位

## 已确定实现要求

- 注册必须使用单次、7 天有效的邀请码，最多 10 个测试账号；邀请码只保存哈希值并在注册事务中原子消费
- 邮箱验证 24 小时有效，密码重置 30 分钟有效；令牌只保存哈希、单次使用，公开响应不泄露账号是否存在
- 分别记录服务条款、隐私政策和第三方 AI 说明的版本与同意时间；未提供律师终稿时保持上线阻断
- 账号删除接口必须重新验证密码、要求“永久删除”和幂等键；立即撤销访问，24 小时内异步清理，保留不含直接身份信息的最小恢复墓碑
- DeepSeek 默认使用官方 `https://api.deepseek.com` 与可配置 `deepseek-v4-flash`，调用前脱敏，禁止自动回退供应商
- 正式主域为 `toudiniuma.cn`，`www` 跳转主域；Secure Cookie、精确插件 Origin 和投递总开关默认关闭
- Chrome 扩展只增加正式域名和 BOSS 最小权限，生成版本化 ZIP 与 SHA-256，不发布商店
- COS 工具必须先客户端加密再上传，并提供校验、恢复和删除墓碑回放流程；真实 Bucket 和密钥由用户之后手动提供

## 验收标准

- 邀请码并发、人数上限、邮箱令牌过期/单次使用、账号枚举防护测试通过
- 三份授权分别记录，缺失任一授权时注册失败
- 删除请求幂等、立即失效、依赖数据清理和恢复墓碑测试通过
- AI 脱敏测试证明电话、邮箱、身份证号和详细住址不进入外发载荷或日志
- 正式域名、Secure Cookie、精确扩展 Origin、BOSS-only 与执行开关测试通过
- COS 脚本在无真实凭据的本地夹具上完成加密、校验、解密恢复测试
- Git diff 无范围外修改、敏感信息、调试输出和临时文件；CI 全绿后创建一个 PR，保持未合并

## 测试命令

```text
./gradlew test --no-daemon --stacktrace
./gradlew build --no-daemon
cd front && pnpm lint
cd front && pnpm test
cd front && pnpm build
node --test chrome-extension/tests/*.test.cjs
node scripts/validate-chrome-extension.mjs
docker compose config --quiet
```

## 返回格式

报告默认分支、任务分支、修改范围、测试结果、提交与远端 SHA、PR/CI 状态、风险、回滚方式，以及仍需用户完成的 ICP、腾讯云资源、法律文本、密钥、上线与真实投递确认门。
