# 第 3 轮：用户系统与多用户隔离实现说明

## 实现边界

Cloud 模式现已提供注册、登录、退出、当前用户、角色拦截、CSRF、Redis Session、登录保护、安全审计和 `user_profiles` 行级隔离。Web 鉴权只使用服务端 Session，不引入 JWT。旧 Windows 本地启动器显式关闭前端认证守卫，并继续使用原 SQLite 入口。

本轮不包含邮箱验证、找回密码、MFA、第三方登录、管理后台、资料编辑、额度业务或其他 SaaS 业务表。`POST /api/me` 的 `quotaSummary` 暂时为空，计划在第 9 轮接入额度数据。

## 数据库与隔离

Flyway `V2__user_system.sql` 增量创建以下表，`V3__audit_action_whitelist.sql` 再以只向前迁移收紧安全事件白名单：

- `app.users`：UUID、标准化唯一邮箱、Argon2id 哈希、`USER/ADMIN` 角色、账号状态、失败次数、锁定和审计时间；
- `app.user_profiles`：用户一对一资料，默认 `Asia/Shanghai` 和 `zh-CN`；
- `app.audit_logs`：只追加的安全事件，不保存密码、Session、CSRF、完整邮箱或原始 IP。

`users` 是认证根表，不启用 RLS。`user_profiles` 启用 RLS，应用角色必须在现有事务内通过 `UserTransactionExecutor` 设置 `set_config('app.current_user_id', ..., true)` 才能读写自己的行。第三个参数 `true` 使上下文只在当前事务有效；连接归还 Hikari 连接池后不会残留身份。

应用角色不能直接增删改审计表，只能调用 `app.append_audit_log(...)` 白名单函数。审计 IP 使用独立 Pepper 做 HMAC-SHA256，User-Agent 只保留粗粒度浏览器/系统类别。

Nginx 仍使用来源 IP 做内存限流并把可信代理头传给 API，但 JSON 访问日志不写原始 IP；常规请求级错误日志也不会落下来源 IP。API 只把 HMAC 后的指纹写入审计表。

## Session、Cookie 与 CSRF

| 场景 | Cookie | Redis 空闲过期 |
| --- | --- | --- |
| 预登录 CSRF Session | 浏览器会话 | 10 分钟 |
| 普通登录 | 浏览器会话 | 12 小时 |
| “记住我”登录 | 持久 Cookie | 30 天 |

Cookie 固定命名为 `AJP_SESSION`，使用 `HttpOnly`、`SameSite=Lax`、`Path=/`。正式 HTTPS 必须配置 `AUTH_COOKIE_SECURE=true`；本机 HTTP 开发保持 `false`。

注册和登录会轮换 Session ID 并生成新 CSRF Token。所有 POST/PUT/PATCH/DELETE 使用 Session 内 CSRF Token，通过 `X-CSRF-TOKEN` 发送。匿名 `GET /api/auth/csrf` 用于创建短期预登录 Session。已退出且没有 Session 时再次退出返回成功；有效登录 Session 的退出仍必须通过 CSRF，并立即删除 Redis Session。

## API 与错误模型

公共认证接口：

- `GET /api/auth/csrf`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/me`（需要 `USER` 或 `ADMIN`，且必须携带 Session 对应的 CSRF Token）

响应统一为 `{ success, data/error, requestId }`。稳定认证错误码包括 `AUTH_REQUIRED`、`INVALID_CREDENTIALS`、`ACCOUNT_LOCKED`、`ACCOUNT_DISABLED`、`CSRF_INVALID`、`RATE_LIMITED`、`VALIDATION_ERROR` 和 `EMAIL_ALREADY_REGISTERED`。JSON 未知字段会被拒绝，因此客户端不能通过 `user_id`、`role` 或 `status` 注入身份。

## 密码与登录保护

密码使用 Argon2id 单向哈希，只接受 12–128 个字符，不要求固定的大小写、数字或符号组合。服务端拒绝控制字符、内置常见泄露密码及与邮箱高度相似的密码。不存在邮箱同样执行等成本假哈希，并统一返回“账号或密码错误”。

默认登录保护：

- 单 IP：10 次/分钟；
- 单邮箱：5 次/15 分钟；
- 连续失败 5 次锁定 15 分钟，后续失败按 30、60 分钟递增，最长 24 小时；
- 登录成功后清零失败计数；
- 账号锁定或禁用时撤销该用户全部 Redis Session；
- Nginx 对注册和登录增加入口限流，超限返回 HTTP 429 和 `Retry-After`。

IP 和邮箱限流键均使用独立 `auth_hash_pepper` 做 HMAC。启动脚本会在被 Git 忽略的 `.secrets/auth_hash_pepper` 自动生成真实值。

## 前端行为

Cloud Web 提供响应式 `/login`、`/register`、`/terms` 和 `/privacy`。认证上下文只把 CSRF Token 放在 React 内存；401 会清除当前用户并跳转登录页；只有收到安全过滤器的 `CSRF_INVALID` 才重新取 Token 并重试一次。

工作台路由默认受保护，未登录跳转 `/login?next=...`。`next` 只接受站内绝对路径，登录后不会跳转到外部站点。侧边栏显示脱敏邮箱、角色和退出入口。服务条款目前是试用版草案，正式上线前必须由法律专业人士审核。

## 验证与回滚

自动测试覆盖迁移、约束、应用角色权限、RLS、连接复用、注册登录、Argon2id、CSRF、Session 轮换与撤销、锁定、禁用账号、未知 `user_id` 字段和站内跳转校验。CI 还会启动完整 Compose，并实际执行注册、`/api/me` 和退出冒烟。

V2/V3 是增量且不可回写的迁移。应用故障时可回退到第 2 轮镜像并保留新增空表；已经产生用户数据后禁止删除表或修改历史迁移，必须通过新的 Flyway 修复迁移处理。
