# AI-JobPilot-Cloud 备份与恢复手册

本文是 P10 的备份与恢复操作基线。仓库现提供可选的 AGE 客户端加密 + COSCLI 上传/回读校验脚本，但不会自行创建腾讯云 Bucket、生命周期、定时器、告警或凭证，也不能仅凭脚本存在声称完成了生产备份。小范围上线的精确步骤见 `docs/LIMITED_BETA_RUNBOOK.md`。

备份中包含账号、简历元数据、岗位、匹配、投递任务和审计记录，属于最高敏感级数据。备份、恢复和演练都禁止使用真实数据复制到个人电脑或公开路径。

## 1. 备份对象与事实来源

| 对象 | 当前实际位置/配置 | 是否纳入恢复链 | 说明 |
| --- | --- | --- | --- |
| PostgreSQL | Compose 卷 `postgres-data` | 必须 | 用户、简历元数据、岗位、匹配、投递、插件、额度和审计的唯一业务事实源 |
| 加密上传文件 | Compose 卷 `private-storage`，或 `APP_STORAGE_TYPE=s3` 指定的私有 Bucket | 必须 | 原文件和提取内容必须与数据库快照及数据加密 Key 配套恢复 |
| 数据加密 Key 与 Key ID | Docker Secret `.secrets/data_encryption_key`、`APP_DATA_ENCRYPTION_KEY_ID` | 必须 | Key 丢失会使既有简历密文不可读；不得把 Key 明文放入备份目录 |
| 认证 Pepper、数据库/Redis 密码、AI Key | `.secrets/` 或部署平台 Secret/KMS | 必须可恢复 | Secret 只由受限管理员恢复，不写入 Git、日志或普通备份说明 |
| Redis | Compose 卷 `redis-data`，启动脚本已启用 AOF `appendfsync everysec` | 可选 | Session、限流、绑定码和队列缓冲可重建；Redis 不是业务事实源 |
| ClamAV 病毒库 | Compose 卷 `clamav-db` | 否 | 可重新下载，恢复时必须等待健康检查通过 |

PostgreSQL、私有文件和对应 Key 必须使用同一备份时间点或可解释的时间窗口。只恢复数据库而没有文件卷/对象存储，或只恢复文件而没有 Key，都不能判定恢复成功。

## 2. 存放位置、权限与加密

- 本地脚本默认输出到项目 `backups/`；该目录已被 `.gitignore` 排除，但这不等于它适合生产长期保存。
- 腾讯云生产环境应把数据库备份和文件备份发送到独立的私有对象存储/备份卷，生产 CVM 故障不能同时摧毁主数据与备份。
- 备份写入账号只允许创建对象和读取自身备份；恢复账号使用单独的 break-glass 权限，恢复操作需要双人复核并留下工单/审计记录。
- `pg_dump` custom 文件本身不是加密文件。上传对象存储前必须使用 KMS 服务端加密或经批准的 GPG/Envelope Encryption；加密 Key 与备份介质分离保存。
- 备份 Bucket/卷不得挂载到 Nginx 可访问目录，不得通过 `/api`、静态 Web 或临时下载链接公开。
- 生产日志优先使用容器 stdout/stderr 和 Docker 日志轮转；备份路径、对象 Key、用户邮箱、简历正文和 Secret 不写入日志。

## 3. 手动 PostgreSQL 备份

脚本要求 PostgreSQL 容器已运行，并只允许把输出放在项目 `backups/` 目录内：

```powershell
.\scripts\backup_postgres.ps1
.\scripts\backup_postgres.ps1 -OutputPath backups\ai-jobpilot-manual-20260820.dump
```

```bash
./scripts/backup_postgres.sh
./scripts/backup_postgres.sh ai-jobpilot-manual-20260820.dump
```

脚本在容器内执行 `pg_dump --format=custom`，再复制到 `backups/`，并清理容器内临时文件。备份完成后，运维人员还必须：

1. 计算 SHA-256 校验值并与备份元数据分开保存；
2. 加密后上传独立备份介质；
3. 检查对象大小、上传状态和访问权限；
4. 把成功/失败、时间、版本、数据库名称和操作者写入运维记录，不写入密码或简历内容。

## 4. 自动备份与失败告警

仓库提供 `scripts/backup_to_cos.sh`，可手动生成 PostgreSQL、私有文件和匿名删除账本的 AGE 密文并通过 COSCLI 上传、回读校验；**仍不会自动创建腾讯云定时任务或告警资源**。生产部署必须由运维在 CVM systemd timer/cron 或经批准的调度平台配置：

- PostgreSQL：至少每日一次；生产基础目标为每小时一次或使用托管 PostgreSQL PITR/WAL 归档。
- `private-storage`：与数据库同频率做卷快照或 S3 版本化/跨可用区复制；删除标记也必须纳入保护策略。
- Secret/Key：使用 KMS/Secret Manager 的版本化恢复机制，至少保留当前版本和经过批准的旧版本解密窗口。
- 每次任务在成功后执行校验和/对象存在性检查；连续失败、上传失败、容量不足、KMS 解密失败和权限错误必须触发短信/邮件/值班系统告警。
- 自动任务不得把完整备份内容或 Secret 放进告警消息；告警只含任务 ID、时间、错误类别和脱敏资源名。

自动化任务、告警和保留策略在腾讯云上线时属于人工验收项；未看到成功运行记录前，不得勾选“自动备份已验证”。

## 5. PostgreSQL 恢复

### 5.1 默认恢复到独立验证库

恢复是破坏性操作。默认目标必须是 `ai_jobpilot_restore_*`，并且应在独立验证实例或明确隔离的验证数据库执行：

```powershell
.\scripts\restore_postgres.ps1 `
  -BackupPath backups\ai-jobpilot-20260820.dump `
  -TargetDatabase ai_jobpilot_restore_verification `
  -Confirm:$false
```

```bash
./scripts/restore_postgres.sh \
  ai-jobpilot-20260820.dump \
  ai_jobpilot_restore_verification
```

PowerShell 和 Bash 脚本都会校验备份路径和目标库名。覆盖生产库必须显式使用生产参数并再次确认；没有工单、双人复核和最新临时备份时禁止覆盖主库。

### 5.2 生产恢复顺序

1. 宣布维护窗口，停止 API、AI Worker 和写入流量；保留 Nginx 健康页或返回维护响应。
2. 对当前主库和当前上传存储再做一次临时备份，记录应用镜像、Flyway 版本和配置版本。
3. 恢复 PostgreSQL 到验证实例，先完成第 6 节检查；不要直接把未经验证的备份覆盖主库。
4. 恢复 `private-storage` 卷或 S3 对象版本，并从 Secret/KMS 恢复匹配的 `data_encryption_key` 与 Key ID。
5. 运行迁移容器/`flyway validate` 等版本检查，确认 schema 与应用版本兼容；不得在生产上执行 `flyway clean`。
6. 依次启动 PostgreSQL、Redis、ClamAV、migrate、API、Worker、Web 和 Nginx，观察 `/readyz`、错误率和队列积压。
7. 完成合成账号验证后再恢复真实用户流量，并记录恢复耗时、缺失数据、失败原因和改进项。

## 6. 独立验证库恢复演练

每季度至少一次，或每次数据库大版本/存储架构变更后，使用不含真实简历的合成备份做演练。验证库必须与生产实例、生产 Secret 访问路径和公开入口隔离；不能把生产 dump 下载到个人电脑。

最低验收证据：

- `pg_restore` 完成且无未处理错误；迁移版本和应用镜像匹配；
- 以 `jobpilot_app` 连接时不能直接读取 `app.audit_logs`，业务读写权限符合最小权限；
- 设置测试用户租户上下文后只能看到该用户数据，无上下文时受保护表不返回数据；
- 合成用户可以登录、读取简历元数据、岗位、匹配和投递任务；
- 使用恢复的 Key ID 解密一份合成简历，文件与提取文本可读；
- Redis 丢失时用户需要重新登录/重新绑定，但 PostgreSQL 中的事实数据仍可读取，未完成 outbox 可重新发布；
- 保存演练开始/结束时间、RPO/RTO 实测值、失败项和责任人。

当前仓库未执行上述腾讯云演练，因此所有结果必须在上线清单中标记为“待人工验证”。

## 7. Redis AOF 与恢复取舍

`docker/redis/start-redis.sh` 当前实际生成 `appendonly yes`、`appendfsync everysec`、`protected-mode yes` 和私有密码配置，AOF 会持久化到 `redis-data`。AOF 的作用是降低重启时 Session/限流/队列缓冲的丢失量，**它不能替代 PostgreSQL 或上传文件备份**。

默认恢复策略是不把 Redis 当作事实源：

- Redis 丢失后，Session 失效，用户重新登录；
- 限流计数和已过期绑定码自然重建；
- PostgreSQL outbox/任务状态是事实来源，Worker 依据数据库重新发布可重试消息；
- 只有在需要缩短恢复时间时才恢复 `redis-data` 的 AOF，并在恢复后验证过期时间、Stream 和权限。

Redis AOF/卷备份是否纳入腾讯云自动策略是人工取舍项；无论取舍如何，都不能因此减少 PostgreSQL、文件和 Key 的备份要求。

## 8. 上传文件与 S3 备份方案

- `APP_STORAGE_TYPE=local`：上传文件在容器私有卷 `private-storage` 的 `/var/lib/ai-jobpilot/private`，不得从宿主机公开目录或 Nginx 直接读取。生产应使用云盘快照/备份服务或停写后的一致性卷备份。
- `APP_STORAGE_TYPE=s3`：使用私有 Bucket，打开版本化、服务端 KMS 加密、生命周期和跨可用区/异地复制；访问凭证仅通过部署 Secret/标准凭证链注入。
- 文件备份必须覆盖原文件、提取文本及删除标记/版本；恢复后抽样验证对象 Key、数据库元数据和解密 Key ID 一致。
- 上传文件备份与数据库备份应记录同一时间窗口。若无法保证严格原子快照，必须记录时间差并在恢复演练中验证新增/删除边界。

## 9. Key 恢复与轮换

恢复简历至少需要：`data_encryption_key`、`APP_DATA_ENCRYPTION_KEY_ID`、对应 KMS/Secret 版本和私有文件对象。`auth_hash_pepper`、数据库密码、Redis 密码和 AI Key 也必须能从 Secret Manager 恢复，但它们不应写入 dump 或文件备份。

Key 轮换采用保留旧 Key ID 解密、使用新 Key 加密的窗口；轮换、撤销和恢复都要有工单。若数据加密 Key 永久丢失，不能声称可以恢复既有简历，应将该事件作为不可逆数据丢失处理并通知受影响用户。

## 10. RPO、RTO 与保留

以下是上线前目标，不是已经测得的结果：

| 环境 | PostgreSQL RPO 目标 | RTO 目标 | 说明 |
| --- | --- | --- | --- |
| 本地/测试 | 24 小时 | 2 小时 | 手动 dump + 私有卷备份 |
| 腾讯云小范围试用 | ≤ 1 小时 | ≤ 30 分钟 | 定时备份/对象版本化，需实测确认 |
| 更高可用生产 | ≤ 15 分钟 | ≤ 15 分钟 | 托管 PostgreSQL PITR/WAL、跨区文件复制，超出当前仓库自动化范围 |

普通备份建议保留 30 天并按隐私政策、法定留存和安全事件保全要求调整。用户删除不能宣称立即改写所有不可变备份；应用数据和在线对象先完成清除，备份在自然过期/轮换窗口结束后删除或按批准流程做不可逆匿名化。法律保全期间不得提前删除，但必须记录范围和到期时间。

## 11. 禁止事项

- 禁止提交 `.dump`、AOF/RDB、`.env`、`.secrets/`、数据库、日志、上传文件或真实简历；
- 禁止把恢复库暴露到公网，禁止把 5432、6379、8888、8889、6866 或 8080 开放到公网；
- 禁止在未验证备份前删除唯一副本；
- 禁止把生产备份复制到个人电脑、聊天工具、Issue 或 CI Artifact；
- 禁止把恢复失败的“命令已启动”当作成功证据，必须保存退出码和验证结果。
