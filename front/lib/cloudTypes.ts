/**
 * Cloud Web 前端共享类型与展示辅助，与后端 DTO 一一对应：
 * - JobModels / MatchModels（GET /api/jobs、/api/jobs/{id}、/api/jobs/{id}/match）
 * - DeliveryModels（/api/delivery/tasks 系列）
 * - PluginModels（/api/plugin/devices、bind-code）
 * 状态/平台/推荐等级中文映射集中维护，未知值回退显示原值。
 */

// ---- 基础类型 ----

export type Salary = {
  minK: number | null
  maxK: number | null
  months: number | null
  text: string | null
}

export type PageResult<T> = {
  items: T[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

// ---- 岗位与匹配 ----

export type MatchSummary = {
  id: string
  score: number | null
  decision: string | null
  greeting: string | null
  status: string
  completedAt: string | null
}

export type TaskStatusRef = {
  id: string
  status: string
  createdAt: string | null
  confirmedAt: string | null
}

export type JobSummary = {
  id: string
  platform: string
  title: string
  companyName: string
  salary: Salary | null
  location: string | null
  status: string
  latestMatchSummary: MatchSummary | null
  deliveryTaskStatus: TaskStatusRef | null
  lastSeenAt: string | null
}

export type MatchErrorInfo = {
  code: string | null
  message: string | null
}

export type MatchView = {
  id: string
  jobId: string
  resumeId: string | null
  preferenceId: string | null
  status: string
  score: number | null
  decision: string | null
  summary: string | null
  strengths: string[]
  risks: string[]
  greeting: string | null
  priorityCompany: { name: string | null; label: string | null } | null
  model: { provider: string | null; name: string | null; promptVersion: string | null } | null
  usage: { inputTokens: number | null; outputTokens: number | null; durationMs: number | null } | null
  error: MatchErrorInfo | null
  attemptCount: number
  createdAt: string | null
  completedAt: string | null
}

export type TaskDetailRef = {
  id: string
  status: string
  greeting: string | null
  version: number
  confirmationVersion: number
  confirmedAt: string | null
  createdAt: string | null
  finishedAt: string | null
}

export type JobDetail = {
  id: string
  platform: string
  externalJobId: string | null
  title: string
  companyName: string
  salary: Salary | null
  location: string | null
  experience: string | null
  degree: string | null
  description: string | null
  jobUrl: string | null
  companyInfo: Record<string, unknown> | null
  skills: string[]
  welfare: string[]
  status: string
  capturedAt: string | null
  lastSeenAt: string | null
  latestMatch: MatchView | null
  deliveryTask: TaskDetailRef | null
}

export type QueuedResult = {
  matchId: string
  jobId: string
  status: string
  queuedAt: string
  reusedExisting: boolean
}

// ---- 投递任务 ----

export type JobRef = {
  id: string
  platform: string
  title: string
  companyName: string
  jobUrl: string | null
  salary: Salary | null
  location: string | null
}
export type MatchRef = {
  id: string
  score: number | null
  decision: string | null
  summary: string | null
  strengths: string[]
  risks: string[]
}
export type DeviceRef = { id: string; deviceName: string | null }
export type TaskErrorInfo = { code: string | null; message: string | null; retryable: boolean | null }

/**
 * 投递清单全局统计（GET /api/delivery/tasks/summary）：服务端聚合，
 * 不受分页影响；平台/关键词/推荐筛选与列表同口径。字段与后端
 * DeliveryModels.SummaryResult 一一对应（P8 规范状态口径）。
 */
export type DeliverySummary = {
  waitingConfirm: number
  confirmed: number
  pulledByPlugin: number
  running: number
  success: number
  failed: number
  skipped: number
  pausedNeedUser: number
  total: number
}

export type EventView = {
  id: number
  eventType: string
  fromStatus: string | null
  toStatus: string | null
  actorType: string | null
  createdAt: string
  details: Record<string, unknown> | null
}

export type TaskListItem = {
  id: string
  status: string
  greeting: string | null
  version: number
  confirmationVersion: number
  confirmedAt: string | null
  job: JobRef
  // 兼容早期允许空 match 链接的历史任务
  match: MatchRef | null
  device: DeviceRef | null
  lastEvent: EventView | null
  createdAt: string | null
  updatedAt: string | null
}

export type TaskDetail = {
  id: string
  jobPostId: string
  // 兼容早期允许空 match 链接的历史任务
  jobMatchId: string | null
  status: string
  greeting: string | null
  version: number
  confirmationVersion: number
  confirmedAt: string | null
  assignedDeviceId: string | null
  attemptCount: number
  lastError: TaskErrorInfo | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string | null
  updatedAt: string | null
  job: JobRef
  // 兼容早期允许空 match 链接的历史任务
  match: MatchRef | null
  device: DeviceRef | null
  events: EventView[]
}

export type TaskView = {
  id: string
  jobPostId: string
  jobMatchId: string
  status: string
  greeting: string | null
  version: number
  confirmationVersion: number
  confirmedAt: string | null
  createdAt: string | null
}

export type GreetingResult = {
  id: string
  greeting: string | null
  status: string
  confirmationRequired: boolean
  version: number
}

export type ConfirmResult = {
  id: string
  status: string
  confirmationVersion: number
  confirmedAt: string | null
  assignedDeviceId: string | null
  version: number
}

export type SkipResult = {
  id: string
  status: string
  finishedAt: string | null
  version: number
}

// ---- 插件设备 ----

export type DeviceView = {
  id: string
  deviceName: string | null
  browserName: string | null
  browserVersion: string | null
  extensionVersion: string | null
  status: string
  capabilities: string[]
  lastSeenAt: string | null
  boundAt: string | null
  revokedAt: string | null
  revokeReason: string | null
}

export type BindCodeResult = {
  bindCode: string
  expiresAt: string
  expiresInSeconds: number
}

export type RevokeDeviceResult = {
  id: string
  status: string
  revokedAt: string | null
}

// ---- 中文映射（未知值回退显示原值） ----

export const PLATFORM_LABELS: Record<string, string> = {
  BOSS: "Boss直聘",
  ZHILIAN: "智联招聘",
  LIEPIN: "猎聘",
  JOB51: "51job",
}

export const JOB_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "有效",
  EXPIRED: "已过期",
  REMOVED: "已下架",
}

export const MATCH_STATUS_LABELS: Record<string, string> = {
  PENDING: "排队中",
  PROCESSING: "分析中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
}

export const DECISION_LABELS: Record<string, string> = {
  APPLY: "推荐投递",
  REVIEW: "谨慎投递",
  SKIP: "不建议投递",
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  // P8 规范状态（持久层与所有 DTO 共用同一口径）
  WAITING_CONFIRM: "待确认",
  CONFIRMED: "已确认",
  PULLED_BY_PLUGIN: "已领取",
  RUNNING: "执行中",
  SUCCESS: "投递成功",
  FAILED: "投递失败",
  SKIPPED: "已跳过",
  PAUSED_NEED_USER: "需用户处理",
  // 兼容旧持久层命名（历史数据/旧客户端展示回退）
  PENDING_CONFIRMATION: "待确认",
  LEASED: "已领取",
  EXECUTING: "执行中",
  SUCCEEDED: "投递成功",
  PAUSED: "已暂停",
  CANCELLED: "已取消",
}

/** P8 投递清单的八个规范状态（筛选只使用这些值）。 */
export const P6_TASK_STATUSES = [
  "WAITING_CONFIRM", "CONFIRMED", "PULLED_BY_PLUGIN", "RUNNING",
  "SUCCESS", "FAILED", "SKIPPED", "PAUSED_NEED_USER",
] as const

/**
 * P8 统一失败/暂停原因码展示表：只包含规范八类（last_error_code 的 CHECK
 * 约束同口径）。触发类原因（USER_REQUESTED / FAILURE_THRESHOLD /
 * MAX_ATTEMPTS_EXCEEDED）不会持久化到 last_error_code，展示兜底返回原值；
 * popup 的暂停原因说明走本地固定文案，不复用此表。
 */
export const ERROR_CODE_LABELS: Record<string, string> = {
  LOGIN_REQUIRED: "登录失效，请在招聘平台页面重新登录",
  CAPTCHA_REQUIRED: "页面要求人工验证",
  RISK_CONTROL: "平台风控拦截",
  JOB_EXPIRED: "岗位已关闭或过期",
  BUTTON_NOT_FOUND: "未找到可用投递按钮",
  PAGE_STRUCTURE_CHANGED: "页面结构与预期不符",
  NETWORK_ERROR: "网络或页面加载异常",
  UNKNOWN_ERROR: "未知错误",
}

export const DEVICE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "有效",
  REVOKED: "已撤销",
}

export const EVENT_TYPE_LABELS: Record<string, string> = {
  CREATED: "创建任务",
  GREETING_UPDATED: "修改招呼语",
  CONFIRMATION_INVALIDATED: "确认失效",
  CONFIRMED: "确认投递",
  SKIPPED: "跳过任务",
  LEASED: "设备领取",
  PULLED: "设备领取",
  STARTED: "开始执行",
  PAUSED: "暂停",
  BATCH_PAUSED: "批量暂停",
  RESUME_REQUESTED: "请求恢复",
  SUCCEEDED: "投递成功",
  FAILED: "投递失败",
  LEASE_EXPIRED: "租约过期",
  DEVICE_REVOKED: "设备已撤销",
  CANCELLED: "已取消",
}

export function labelOf(labels: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "—"
  return labels[value] ?? value
}

export function platformLabel(platform: string | null | undefined): string {
  return labelOf(PLATFORM_LABELS, platform)
}

// ---- 徽章色调（与中文映射一起集中维护） ----

export const DECISION_BADGE_CLASS: Record<string, string> = {
  APPLY: "bg-emerald-50 text-emerald-700",
  REVIEW: "bg-amber-50 text-amber-700",
  SKIP: "bg-slate-100 text-slate-500",
}

export const MATCH_STATUS_BADGE_CLASS: Record<string, string> = {
  PENDING: "bg-sky-50 text-sky-700",
  PROCESSING: "bg-blue-50 text-blue-700",
  SUCCEEDED: "bg-emerald-50 text-emerald-700",
  FAILED: "bg-rose-50 text-rose-700",
}

export const TASK_STATUS_BADGE_CLASS: Record<string, string> = {
  WAITING_CONFIRM: "bg-amber-50 text-amber-700",
  PAUSED_NEED_USER: "bg-rose-50 text-rose-700",
  PENDING_CONFIRMATION: "bg-amber-50 text-amber-700",
  CONFIRMED: "bg-sky-50 text-sky-700",
  PULLED_BY_PLUGIN: "bg-blue-50 text-blue-700",
  RUNNING: "bg-blue-50 text-blue-700",
  SUCCESS: "bg-emerald-50 text-emerald-700",
  FAILED: "bg-rose-50 text-rose-700",
  LEASED: "bg-blue-50 text-blue-700",
  EXECUTING: "bg-blue-50 text-blue-700",
  SUCCEEDED: "bg-emerald-50 text-emerald-700",
  PAUSED: "bg-orange-50 text-orange-700",
  SKIPPED: "bg-slate-100 text-slate-500",
  CANCELLED: "bg-slate-100 text-slate-500",
}

export function badgeClassFor(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "bg-slate-100 text-slate-600"
  return map[value] ?? "bg-slate-100 text-slate-600"
}

// ---- 格式化 ----

export function formatSalary(salary: Salary | null | undefined): string {
  if (!salary) return "薪资面议"
  if (salary.text) return salary.text
  if (salary.minK !== null && salary.maxK !== null) {
    return `${salary.minK}-${salary.maxK}K${salary.months ? `·${salary.months}薪` : ""}`
  }
  return "薪资面议"
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return "—"
  return date.toLocaleString("zh-CN", { hour12: false })
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return "—"
  return date.toLocaleDateString("zh-CN")
}

// ---- 幂等与校验 ----

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isValidUuid(value: string): boolean {
  return UUID_PATTERN.test(value)
}

/**
 * 每次新的用户动作生成一个非空且不超过 128 字符的幂等键。
 * 同一动作的网络重试由 secureRequest 层处理；渲染期间不得生成，
 * 也不得把同一键复用到另一种操作。
 */
export function newIdempotencyKey(): string {
  const browserCrypto = globalThis.crypto
  if (browserCrypto && typeof browserCrypto.randomUUID === "function") {
    const id = browserCrypto.randomUUID()
    if (id) return id
  }
  if (browserCrypto && typeof browserCrypto.getRandomValues === "function") {
    const bytes = browserCrypto.getRandomValues(new Uint8Array(16))
    return `id-${Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("")}`
  }
  throw new Error("当前浏览器无法生成安全幂等标识，请升级浏览器后重试")
}

/** Unicode code point 计数（招呼语长度展示/提示，服务端仍是最终校验）。 */
export function countCodePoints(value: string): number {
  // Array.from 按 code point 迭代，避免把代理对算成两个字符
  return Array.from(value).length
}
