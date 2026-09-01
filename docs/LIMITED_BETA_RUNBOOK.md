# 投递牛马小范围上线运行手册

目标地址为 `https://toudiniuma.cn`，首轮 6–10 人，仅开放 BOSS 直聘适配。本文是部署准备说明，不代表备案、腾讯云资源、真实邮件、真实 AI 或真实投递已经验证。

## 1. 当前强制边界

- `LIMITED_BETA_ENABLED=false`：三份律师终稿、SES、域名和 HTTPS 未完成前保持关闭。
- `DELIVERY_EXECUTION_ENABLED=false`：正式域名模拟验收前保持关闭。
- `BETA_MAX_USERS=10`：服务端事务内锁定名额并消费单次邀请码。
- Chrome 插件固定 ID 为 `ompipmnadogogfbebnmjgbbcadildpbc`，发布包静态权限只含正式域名和 BOSS 域名；首期不申请智联权限，也不执行智联任务。
- DeepSeek 默认地址为 `https://api.deepseek.com`，模型为 `deepseek-v4-flash`；不自动切换供应商。

启用 `LIMITED_BETA_ENABLED=true` 时，后端会强制检查邀请码、邮箱验证、Secure Cookie、三份正式版本号、腾讯云 SES、正式 URL、10 人上限和插件最低版本。任一项缺失都会拒绝启动，防止把草稿配置误当正式上线。

## 2. 人工材料门禁

上线操作前逐项取得并保存证据：

1. ICP 备案通过通知；腾讯云控制台确认 CVM 正常、公网 IP 正确、安全组只开放 80/443，SSH 只对受控来源开放。
2. 私有 COS：与服务器不同地域、禁止公共访问、启用版本控制、普通备份 30 天生命周期。
3. 腾讯云 SES：发信域名与 `noreply@toudiniuma.cn` 审核通过，验证与重置模板审核通过。
4. DeepSeek 官方 API Key 和小额余额；Key 只写 `.secrets/ai_api_key`。
5. 律师最终版《用户协议》《隐私政策》《第三方 AI 数据处理说明》及正式生效日期。把终稿交给 Codex 原样替换三个占位页面，不能自行改写法律文本。
6. AGE 恢复私钥离线保存两份；服务器只保存对应的 `AGE_RECIPIENT` 公钥。

## 3. Secret 与生产参数

Secret 文件均不得进入 Git、聊天、日志或 `.env`：

```text
.secrets/db_owner_password
.secrets/db_app_password
.secrets/redis_password
.secrets/auth_hash_pepper
.secrets/data_encryption_key
.secrets/ai_api_key
.secrets/tencentcloud_ses_secret_id
.secrets/tencentcloud_ses_secret_key
```

律师终稿和 SES 审核通过后，生产 `.env` 至少设置：

```text
APP_PUBLIC_URL=https://toudiniuma.cn
AUTH_COOKIE_SECURE=true
AUTH_INVITE_REQUIRED=true
AUTH_EMAIL_VERIFICATION_REQUIRED=true
AUTH_LEGAL_DOCUMENTS_FINALIZED=true
AUTH_TERMS_VERSION=正式生效日期
AUTH_PRIVACY_VERSION=正式生效日期
AUTH_AI_DISCLOSURE_VERSION=正式生效日期
AUTH_EMAIL_PROVIDER=tencent-ses
TENCENT_SES_VERIFICATION_TEMPLATE_ID=审核通过的模板ID
TENCENT_SES_PASSWORD_RESET_TEMPLATE_ID=审核通过的模板ID
BETA_MAX_USERS=10
PLUGIN_MIN_EXTENSION_VERSION=1.6.0
DELIVERY_EXECUTION_ENABLED=false
LIMITED_BETA_ENABLED=true
```

## 4. 邀请码管理

邀请码明文只在生成时显示一次，数据库仅保存 SHA-256。默认 7 天有效、单次使用：

```powershell
.\scripts\manage_beta_invites.ps1 -Action Generate -ValidDays 7
.\scripts\manage_beta_invites.ps1 -Action List
.\scripts\manage_beta_invites.ps1 -Action Revoke -InviteId <UUID>
```

Linux 服务器使用：

```bash
./scripts/manage_beta_invites.sh generate 7
./scripts/manage_beta_invites.sh list
./scripts/manage_beta_invites.sh revoke <UUID>
```

一个邀请码只发给一名测试者，不在群聊或公开文档中发送。

## 5. 加密 COS 备份与隔离恢复

服务器安装并配置 `age`、`coscli`、`jq`；COSCLI 使用只允许写入/回读本备份前缀的最小权限账号。每日任务执行：

```bash
export AGE_RECIPIENT='age1...公钥'
export COS_BACKUP_URI='cos://私有桶别名/ai-jobpilot'
./scripts/backup_to_cos.sh
```

脚本会备份 PostgreSQL、私有文件卷和 180 天匿名删除账本；上传前 AGE 加密，记录 SHA-256、大小、UTC 时间和 Flyway 版本，上传后从 COS 回读逐字节校验。失败必须由外部 systemd/监控通过腾讯云邮件告警，脚本本身不会在日志中输出 Secret 或用户数据。

隔离恢复必须由你提供离线私钥，并且目标数据库只能以 `ai_jobpilot_restore_` 开头：

```bash
export AGE_IDENTITY_FILE='/受控路径/recovery-key.txt'
./scripts/restore_from_cos.sh \
  'cos://私有桶别名/ai-jobpilot/ai-jobpilot-YYYYMMDDTHHMMSSZ' \
  ai_jobpilot_restore_verification
```

恢复脚本会先校验密文，再解密到 Git 忽略的隔离目录、恢复验证库并回放删除账本。之后人工验证一个含非空合成简历和附件的账号；不得直接把验证库切成生产库。

## 6. 部署确认单

PR 合并后、操作生产前记录：

- 合并提交 SHA 与精确镜像 Tag；
- 最近一次已回读校验的 COS 备份集；
- 当前 Flyway 版本与待执行迁移 `V12__limited_beta_accounts.sql`；
- 当前 DNS、服务器公网 IP、证书路径；
- 应用回滚镜像和数据库向前兼容说明；
- `DELIVERY_EXECUTION_ENABLED=false` 的截图或配置证据。

先备份，再运行迁移和精确镜像。迁移失败时不启动新应用；应用失败回滚到记录的旧镜像。数据库禁止 `flyway clean`、禁止直接覆盖生产库。DNS、生产迁移和正式上线必须再次取得用户确认。

## 7. 放量顺序

1. 站长合成账号完成注册、邮箱、插件绑定、匹配、模拟任务、删除与备份恢复。
2. 2 名测试者完成注册、插件手动安装、匹配和模拟任务。
3. 用户明确确认后才把投递开关打开；每项真实投递仍由当前测试者单独确认。
4. 每人只验证 1 项真实 BOSS 投递。验证码、滑块和风控由本人处理，状态不明立即停止。
5. 无重复投递、数据泄露、删除失败后再扩展到 6–10 人。

发现异常时先把 `DELIVERY_EXECUTION_ENABLED=false` 并重启 API；资料管理功能继续可用。监控资源、错误率、AI 费用、任务队列、删除任务和每日备份回读结果。
