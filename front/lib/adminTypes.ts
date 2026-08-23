/**
 * 后台管理（/api/admin）前端类型与展示辅助，与后端 AdminModels 一一对应：
 * - DashboardView / UserPage / UserAdminView / ResourceQuotaView
 * - UserQuotaRowView / QuotaAdjustResult / AuditLogView / DeliveryFailureView
 *
 * 这些字段都是后端已脱敏/聚合后的窄字段：邮箱始终为 emailMasked，绝不包含
 * password/passwordHash、完整 email、Token/API Key/Cookie、简历、Prompt、
 * audit details/ipHash/requestId 等敏感信息。未知枚举值一律回退显示原值。
 */

export type ResourceQuotaView = {
  resourceCode: string
  total: number
  used: number
  reserved: number
  remaining: number
}

/** 后台用户视图（列表行与详情共用；列表行携带总条数）。 */
export type UserAdminView = {
  id: string
  emailMasked: string
  role: string
  status: string
  createdAt: string | null
  plan: string
  analysisQuota: ResourceQuotaView
  deliveryQuota: ResourceQuotaView
  jobCount: number
  aiAnalysisCount: number
  deliveryTaskCount: number
  successCount: number
  failedCount: number
  activeDeviceCount: number
  totalCount: number
}

/** 用户分页列表。 */
export type UserPage = {
  total: number
  users: UserAdminView[]
}

/** 单用户当前周期额度行。 */
export type UserQuotaRowView = {
  quotaId: string
  plan: string
  resourceCode: string
  total: number
  used: number
  reserved: number
  remaining: number
  resetAt: string | null
}

/** 仪表盘聚合。 */
export type DashboardView = {
  totalUsers: number
  activeUsers: number
  jobs: number
  aiAnalyses: number
  deliveryTasks: number
  successCount: number
  failedCount: number
  activeDevices: number
  recentFailures: number
}

/** 最近审计事件（不含 details/ip_hash/user_agent_summary/request_id）。 */
export type AuditLogView = {
  id: number
  userId: string
  userEmailMasked: string
  actorType: string
  action: string
  targetType: string
  targetId: string | null
  result: string
  createdAt: string | null
}

/** 最近失败投递任务。 */
export type DeliveryFailureView = {
  taskId: string
  userId: string
  emailMasked: string
  platform: string
  status: string
  lastErrorCode: string | null
  errorMessage: string | null
  updatedAt: string | null
}

/** PUT /api/admin/users/{id}/quota 响应中的当前状态。 */
export type QuotaAdjustResult = {
  plan: string
  analysisQuota: ResourceQuotaView
  deliveryQuota: ResourceQuotaView
}

export const ADMIN_PLANS = ["FREE", "MONTHLY", "PREMIUM_MONTHLY", "JOB_SEASON", "COACHING"] as const

export const ADMIN_PLAN_LABELS: Record<string, string> = {
  FREE: "免费版",
  MONTHLY: "月卡",
  PREMIUM_MONTHLY: "高级月卡",
  JOB_SEASON: "求职季卡",
  COACHING: "人工陪跑版",
}

export const ADMIN_ROLE_LABELS: Record<string, string> = {
  USER: "普通用户",
  ADMIN: "管理员",
}

export const ADMIN_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "正常",
  LOCKED: "已锁定",
  DISABLED: "已禁用",
  PENDING: "待激活",
}

export const ADMIN_RESOURCE_LABELS: Record<string, string> = {
  AI_ANALYSIS: "AI 分析",
  DELIVERY_CONFIRM: "投递",
}

/** 审计动作中文标签；未知动作回退显示原值。 */
export const ADMIN_AUDIT_ACTION_LABELS: Record<string, string> = {
  ADMIN_QUOTA_ADJUSTED: "调整额度",
  AUTH_REGISTER: "注册",
  AUTH_LOGIN: "登录",
  AUTH_LOGOUT: "退出",
  AUTH_LOGIN_FAILED: "登录失败",
  RESUME_UPLOAD: "上传简历",
  PREFERENCE_UPDATED: "更新求职目标",
  JOB_ANALYSIS_REQUESTED: "发起分析",
  JOB_ANALYSIS_SUCCEEDED: "分析成功",
  JOB_ANALYSIS_FAILED: "分析失败",
  PLUGIN_DEVICE_BOUND: "绑定设备",
  PLUGIN_DEVICE_REVOKED: "撤销设备",
  DELIVERY_TASK_CREATED: "创建投递任务",
  DELIVERY_TASK_CONFIRMED: "确认投递",
  DELIVERY_TASK_SKIPPED: "跳过投递",
  PLUGIN_TASK_SUCCEEDED: "投递成功",
  PLUGIN_TASK_FAILED: "投递失败",
}

export const ADMIN_AUDIT_RESULT_LABELS: Record<string, string> = {
  SUCCESS: "成功",
  FAILED: "失败",
}

export function adminLabel(labels: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "—"
  return labels[value] ?? value
}

/** 额度使用概览：已用/总量，悬停可见 预占/剩余 明细。 */
export function formatQuotaUsage(quota: ResourceQuotaView | null | undefined): string {
  if (!quota) return "—"
  return `${quota.used}/${quota.total}`
}

/** 额度明细 title：总量/已用/预占/剩余。 */
export function quotaDetailTitle(quota: ResourceQuotaView | null | undefined): string {
  if (!quota) return ""
  return `总量 ${quota.total} · 已用 ${quota.used} · 预占 ${quota.reserved} · 剩余 ${quota.remaining}`
}
