"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { BiCog, BiLoaderAlt, BiRefresh, BiShieldAlt } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { formatDateTime, labelOf } from "@/lib/cloudTypes"
import {
  ADMIN_AUDIT_ACTION_LABELS,
  ADMIN_AUDIT_RESULT_LABELS,
  ADMIN_PLAN_LABELS,
  ADMIN_ROLE_LABELS,
  ADMIN_STATUS_LABELS,
  adminLabel,
  formatQuotaUsage,
  quotaDetailTitle,
  type AuditLogView,
  type DashboardView,
  type DeliveryFailureView,
  type UserAdminView,
  type UserPage,
} from "@/lib/adminTypes"

export default function AdminDashboardPage() {
  const { secureRequest } = useAuth()
  const [dashboard, setDashboard] = useState<DashboardView | null>(null)
  const [users, setUsers] = useState<UserAdminView[]>([])
  const [userTotal, setUserTotal] = useState(0)
  const [auditLogs, setAuditLogs] = useState<AuditLogView[]>([])
  const [failures, setFailures] = useState<DeliveryFailureView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    setError("")
    try {
      const [dashboardData, usersData, auditData, failureData] = await Promise.all([
        secureRequest<DashboardView>("/api/admin/dashboard"),
        secureRequest<UserPage>("/api/admin/users?page=0&size=20"),
        secureRequest<AuditLogView[]>("/api/admin/audit-logs?limit=20"),
        secureRequest<DeliveryFailureView[]>("/api/admin/delivery-failures?limit=20"),
      ])
      setDashboard(dashboardData)
      setUsers(usersData.users)
      setUserTotal(usersData.total)
      setAuditLogs(auditData)
      setFailures(failureData)
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "后台数据加载失败")
    } finally {
      setLoading(false)
    }
  }, [secureRequest])

  useEffect(() => {
    void load()
  }, [load])

  const stats: { label: string; value: string }[] = dashboard
    ? [
        { label: "总用户", value: String(dashboard.totalUsers) },
        { label: "活跃用户", value: String(dashboard.activeUsers) },
        { label: "岗位数", value: String(dashboard.jobs) },
        { label: "AI 分析次数", value: String(dashboard.aiAnalyses) },
        { label: "投递任务数", value: String(dashboard.deliveryTasks) },
        { label: "投递成功", value: String(dashboard.successCount) },
        { label: "投递失败", value: String(dashboard.failedCount) },
        { label: "活动插件设备", value: String(dashboard.activeDevices) },
        { label: "近 7 天失败", value: String(dashboard.recentFailures) },
      ]
    : []

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiShieldAlt size={28} />}
        title="后台管理"
        subtitle="用户、额度与系统运行总览（数据来自后端脱敏接口）"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <BiRefresh />
            刷新
          </Button>
        }
      />

      {error && (
        <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center gap-2 py-24 text-sm text-slate-500">
          <BiLoaderAlt className="animate-spin" />
          正在加载后台数据…
        </div>
      ) : error ? (
        <Card>
          <CardContent className="space-y-3 pt-5">
            <p className="text-sm text-slate-500">后台数据加载失败，请稍后刷新重试。</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <BiRefresh />
              重新加载
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-5">
            {stats.map((stat) => (
              <Card key={stat.label}>
                <CardContent className="pt-5">
                  <p className="text-xs text-slate-400">{stat.label}</p>
                  <p className="mt-1 text-2xl font-bold text-slate-950 dark:text-white">{stat.value}</p>
                </CardContent>
              </Card>
            ))}
          </div>

          <Card>
            <CardHeader>
              <CardTitle>用户列表</CardTitle>
              <CardDescription>共 {userTotal} 位用户，展示最近 {users.length} 位</CardDescription>
            </CardHeader>
            <CardContent>
              {users.length === 0 ? (
                <p className="py-6 text-center text-sm text-slate-400">暂无用户</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[1100px] border-collapse text-left text-sm">
                    <thead>
                      <tr className="border-b border-slate-200/80 text-xs text-slate-400 dark:border-white/10">
                        <th className="py-2 pr-3 font-medium">用户</th>
                        <th className="py-2 pr-3 font-medium">角色</th>
                        <th className="py-2 pr-3 font-medium">状态</th>
                        <th className="py-2 pr-3 font-medium">套餐</th>
                        <th className="py-2 pr-3 font-medium">AI 额度</th>
                        <th className="py-2 pr-3 font-medium">投递额度</th>
                        <th className="py-2 pr-3 font-medium">岗位</th>
                        <th className="py-2 pr-3 font-medium">分析</th>
                        <th className="py-2 pr-3 font-medium">任务</th>
                        <th className="py-2 pr-3 font-medium">成功</th>
                        <th className="py-2 pr-3 font-medium">失败</th>
                        <th className="py-2 font-medium">设备</th>
                      </tr>
                    </thead>
                    <tbody>
                      {users.map((user) => (
                        <tr
                          key={user.id}
                          className="border-b border-slate-100/80 transition-colors hover:bg-slate-50/70 dark:border-white/5 dark:hover:bg-white/5"
                        >
                          <td className="max-w-[220px] truncate py-2.5 pr-3">
                            <Link
                              href={`/admin/user?id=${encodeURIComponent(user.id)}`}
                              className="font-medium text-blue-600 hover:underline dark:text-blue-300"
                              title={user.emailMasked}
                            >
                              {user.emailMasked}
                            </Link>
                          </td>
                          <td className="py-2.5 pr-3">{adminLabel(ADMIN_ROLE_LABELS, user.role)}</td>
                          <td className="py-2.5 pr-3">
                            <span
                              className={
                                user.status === "ACTIVE"
                                  ? "rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300"
                                  : "rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600 dark:bg-white/5 dark:text-slate-300"
                              }
                            >
                              {adminLabel(ADMIN_STATUS_LABELS, user.status)}
                            </span>
                          </td>
                          <td className="py-2.5 pr-3">{adminLabel(ADMIN_PLAN_LABELS, user.plan)}</td>
                          <td className="py-2.5 pr-3">
                            <span title={quotaDetailTitle(user.analysisQuota)}>{formatQuotaUsage(user.analysisQuota)}</span>
                          </td>
                          <td className="py-2.5 pr-3">
                            <span title={quotaDetailTitle(user.deliveryQuota)}>{formatQuotaUsage(user.deliveryQuota)}</span>
                          </td>
                          <td className="py-2.5 pr-3 text-slate-600">{user.jobCount}</td>
                          <td className="py-2.5 pr-3 text-slate-600">{user.aiAnalysisCount}</td>
                          <td className="py-2.5 pr-3 text-slate-600">{user.deliveryTaskCount}</td>
                          <td className="py-2.5 pr-3 text-emerald-600">{user.successCount}</td>
                          <td className="py-2.5 pr-3 text-rose-600">{user.failedCount}</td>
                          <td className="py-2.5 text-slate-600">{user.activeDeviceCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-4 xl:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <BiCog className="text-slate-400" />
                  最近失败投递
                </CardTitle>
                <CardDescription>最近 {failures.length} 条失败任务（仅脱敏邮箱）</CardDescription>
              </CardHeader>
              <CardContent>
                {failures.length === 0 ? (
                  <p className="py-6 text-center text-sm text-slate-400">暂无失败记录</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[560px] border-collapse text-left text-sm">
                      <thead>
                        <tr className="border-b border-slate-200/80 text-xs text-slate-400 dark:border-white/10">
                          <th className="py-2 pr-3 font-medium">时间</th>
                          <th className="py-2 pr-3 font-medium">用户</th>
                          <th className="py-2 pr-3 font-medium">平台</th>
                          <th className="py-2 pr-3 font-medium">状态</th>
                          <th className="py-2 font-medium">原因</th>
                        </tr>
                      </thead>
                      <tbody>
                        {failures.map((failure) => (
                          <tr
                            key={failure.taskId}
                            className="border-b border-slate-100/80 dark:border-white/5"
                          >
                            <td className="max-w-[150px] truncate py-2 pr-3 text-xs text-slate-500" title={formatDateTime(failure.updatedAt)}>
                              {formatDateTime(failure.updatedAt)}
                            </td>
                            <td className="max-w-[160px] truncate py-2 pr-3 text-slate-600" title={failure.emailMasked}>
                              {failure.emailMasked}
                            </td>
                            <td className="max-w-[120px] truncate py-2 pr-3 text-slate-600" title={labelOf({ BOSS: "Boss直聘", ZHILIAN: "智联招聘", LIEPIN: "猎聘", JOB51: "51job" }, failure.platform)}>
                              {labelOf({ BOSS: "Boss直聘", ZHILIAN: "智联招聘", LIEPIN: "猎聘", JOB51: "51job" }, failure.platform)}
                            </td>
                            <td className="py-2 pr-3 text-slate-600">{labelOf({ FAILED: "投递失败", PAUSED_NEED_USER: "需用户处理" }, failure.status)}</td>
                            <td className="max-w-[200px] truncate py-2 text-slate-500" title={failure.errorMessage ?? failure.lastErrorCode ?? "—"}>
                              {failure.errorMessage ?? failure.lastErrorCode ?? "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <BiShieldAlt className="text-slate-400" />
                  最近审计记录
                </CardTitle>
                <CardDescription>最近 {auditLogs.length} 条审计事件（不含详情字段）</CardDescription>
              </CardHeader>
              <CardContent>
                {auditLogs.length === 0 ? (
                  <p className="py-6 text-center text-sm text-slate-400">暂无审计记录</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[560px] border-collapse text-left text-sm">
                      <thead>
                        <tr className="border-b border-slate-200/80 text-xs text-slate-400 dark:border-white/10">
                          <th className="py-2 pr-3 font-medium">时间</th>
                          <th className="py-2 pr-3 font-medium">操作</th>
                          <th className="py-2 pr-3 font-medium">用户</th>
                          <th className="py-2 font-medium">结果</th>
                        </tr>
                      </thead>
                      <tbody>
                        {auditLogs.map((audit) => (
                          <tr
                            key={audit.id}
                            className="border-b border-slate-100/80 dark:border-white/5"
                          >
                            <td className="max-w-[150px] truncate py-2 pr-3 text-xs text-slate-500" title={formatDateTime(audit.createdAt)}>
                              {formatDateTime(audit.createdAt)}
                            </td>
                            <td className="max-w-[150px] truncate py-2 pr-3 text-slate-600" title={adminLabel(ADMIN_AUDIT_ACTION_LABELS, audit.action)}>
                              {adminLabel(ADMIN_AUDIT_ACTION_LABELS, audit.action)}
                            </td>
                            <td className="max-w-[160px] truncate py-2 pr-3 text-slate-600" title={audit.userEmailMasked}>
                              {audit.userEmailMasked}
                            </td>
                            <td className="py-2 text-slate-600">{adminLabel(ADMIN_AUDIT_RESULT_LABELS, audit.result)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </div>
  )
}
