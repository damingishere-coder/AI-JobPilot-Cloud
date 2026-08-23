"use client"

import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { FormEvent, useCallback, useEffect, useRef, useState } from "react"
import { BiArrowBack, BiLoaderAlt, BiRefresh } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { formatDateTime, newIdempotencyKey } from "@/lib/cloudTypes"
import {
  ADMIN_PLAN_LABELS,
  ADMIN_PLANS,
  ADMIN_RESOURCE_LABELS,
  ADMIN_ROLE_LABELS,
  ADMIN_STATUS_LABELS,
  adminLabel,
  type QuotaAdjustResult,
  type ResourceQuotaView,
  type UserAdminView,
  type UserQuotaRowView,
} from "@/lib/adminTypes"

const REASON_MAX_LENGTH = 200

export default function AdminUserDetailClient() {
  const searchParams = useSearchParams()
  const userId = searchParams.get("id")
  const { secureRequest } = useAuth()

  const [user, setUser] = useState<UserAdminView | null>(null)
  const [quotaRows, setQuotaRows] = useState<UserQuotaRowView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")

  const [plan, setPlan] = useState("")
  const [analysisTotal, setAnalysisTotal] = useState("")
  const [deliveryTotal, setDeliveryTotal] = useState("")
  const [reason, setReason] = useState("")
  const [submitting, setSubmitting] = useState(false)

  // 幂等键：每次用户主动提交生成新的键；同一失败的提交重试复用当前键。
  // 键只在内存中，绝不展示或持久化到 localStorage。
  const idempotencyRef = useRef<string | null>(null)
  const currentIdempotencyKey = () => {
    if (!idempotencyRef.current) {
      idempotencyRef.current = newIdempotencyKey()
    }
    return idempotencyRef.current
  }
  const resetIdempotencyKey = () => {
    idempotencyRef.current = null
  }

  const loadUser = useCallback(async () => {
    if (!userId) return
    setLoading(true)
    setError("")
    try {
      const detail = await secureRequest<UserAdminView>(`/api/admin/users/${userId}`)
      setUser(detail)
      setPlan(detail.plan)
      setAnalysisTotal(String(detail.analysisQuota.total))
      setDeliveryTotal(String(detail.deliveryQuota.total))
    } catch (requestError) {
      setUser(null)
      setError(requestError instanceof Error ? requestError.message : "用户详情加载失败")
    } finally {
      setLoading(false)
    }
  }, [secureRequest, userId])

  const loadQuota = useCallback(async () => {
    if (!userId) return
    try {
      setQuotaRows(await secureRequest<UserQuotaRowView[]>(`/api/admin/users/${userId}/quota`))
    } catch {
      // 额度行加载失败不打断主流程；总览卡片仍展示详情中的聚合额度。
      setQuotaRows([])
    }
  }, [secureRequest, userId])

  useEffect(() => {
    if (!userId) {
      setError("缺少用户 ID，无法加载用户详情")
      setLoading(false)
      return
    }
    void loadUser()
    void loadQuota()
  }, [loadQuota, loadUser, userId])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!userId || !user) return
    const planValue = plan.trim()
    const analysisTotalValue = Number(analysisTotal)
    const deliveryTotalValue = Number(deliveryTotal)
    const reasonValue = reason.trim()

    if (!planValue) {
      setError("请选择套餐")
      return
    }
    if (!Number.isInteger(analysisTotalValue) || analysisTotalValue < 0) {
      setError("请填写有效的 AI 分析额度（非负整数）")
      return
    }
    if (!Number.isInteger(deliveryTotalValue) || deliveryTotalValue < 0) {
      setError("请填写有效的投递额度（非负整数）")
      return
    }
    if (!reasonValue) {
      setError("请填写调整原因")
      return
    }

    setSubmitting(true)
    setError("")
    setNotice("")
    try {
      const key = currentIdempotencyKey()
      const result = await secureRequest<QuotaAdjustResult>(`/api/admin/users/${userId}/quota`, {
        method: "PUT",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({
          plan: planValue,
          analysisQuotaTotal: analysisTotalValue,
          deliveryQuotaTotal: deliveryTotalValue,
          reason: reasonValue,
        }),
      })
      resetIdempotencyKey()
      setPlan(result.plan)
      setAnalysisTotal(String(result.analysisQuota.total))
      setDeliveryTotal(String(result.deliveryQuota.total))
      setUser((current) =>
        current
          ? {
              ...current,
              plan: result.plan,
              analysisQuota: result.analysisQuota,
              deliveryQuota: result.deliveryQuota,
            }
          : current,
      )
      setReason("")
      setNotice("额度调整成功，已写入审计记录")
      await loadQuota()
    } catch (requestError) {
      // 失败时保留当前幂等键，同一提交重试可复用。
      setError(requestError instanceof Error ? requestError.message : "额度调整失败")
    } finally {
      setSubmitting(false)
    }
  }

  const quotaCard = (title: string, quota: ResourceQuotaView | null | undefined) => (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{quota ? adminLabel(ADMIN_RESOURCE_LABELS, quota.resourceCode) : "暂无数据"}</CardDescription>
      </CardHeader>
      <CardContent>
        {quota ? (
          <div className="grid grid-cols-4 gap-2 text-center text-sm">
            <div>
              <p className="text-xs text-slate-400">总量</p>
              <p className="mt-1 font-semibold text-slate-900 dark:text-white">{quota.total}</p>
            </div>
            <div>
              <p className="text-xs text-slate-400">已用</p>
              <p className="mt-1 font-semibold text-slate-900 dark:text-white">{quota.used}</p>
            </div>
            <div>
              <p className="text-xs text-slate-400">预占</p>
              <p className="mt-1 font-semibold text-slate-900 dark:text-white">{quota.reserved}</p>
            </div>
            <div>
              <p className="text-xs text-slate-400">剩余</p>
              <p className="mt-1 font-semibold text-emerald-600">{quota.remaining}</p>
            </div>
          </div>
        ) : (
          <p className="text-sm text-slate-400">—</p>
        )}
      </CardContent>
    </Card>
  )

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiArrowBack size={28} />}
        title={user ? `用户详情 · ${user.emailMasked}` : "用户详情"}
        subtitle="查看用户基础统计与额度，调整套餐与每月额度"
        actions={
          <>
            <Button asChild variant="outline" size="sm">
              <Link href="/admin">
                <BiArrowBack />
                返回后台
              </Link>
            </Button>
            <Button variant="outline" size="sm" onClick={() => void loadUser()} disabled={loading}>
              <BiRefresh />
              刷新
            </Button>
          </>
        }
      />

      {error && (
        <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
          {error}
        </div>
      )}
      {notice && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
          {notice}
        </div>
      )}

      {loading && !user ? (
        <div className="flex items-center justify-center gap-2 py-24 text-sm text-slate-500">
          <BiLoaderAlt className="animate-spin" />
          正在加载用户详情…
        </div>
      ) : user ? (
        <>
          <div className="grid gap-4 xl:grid-cols-[1fr_320px]">
            <Card>
              <CardHeader>
                <CardTitle>用户基础信息</CardTitle>
                <CardDescription>注册于 {formatDateTime(user.createdAt)}</CardDescription>
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <p>
                  <span className="text-slate-400">邮箱：</span>
                  <span title={user.emailMasked}>{user.emailMasked}</span>
                </p>
                <p>
                  <span className="text-slate-400">角色：</span>
                  {adminLabel(ADMIN_ROLE_LABELS, user.role)}
                </p>
                <p>
                  <span className="text-slate-400">状态：</span>
                  {adminLabel(ADMIN_STATUS_LABELS, user.status)}
                </p>
                <p>
                  <span className="text-slate-400">当前套餐：</span>
                  {adminLabel(ADMIN_PLAN_LABELS, user.plan)}
                </p>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>业务统计</CardTitle>
                <CardDescription>该用户名下汇总数据</CardDescription>
              </CardHeader>
              <CardContent className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <p className="text-xs text-slate-400">岗位数</p>
                  <p className="mt-1 font-semibold text-slate-900 dark:text-white">{user.jobCount}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-400">AI 分析次数</p>
                  <p className="mt-1 font-semibold text-slate-900 dark:text-white">{user.aiAnalysisCount}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-400">投递任务数</p>
                  <p className="mt-1 font-semibold text-slate-900 dark:text-white">{user.deliveryTaskCount}</p>
                </div>
                <div>
                  <p className="text-xs text-slate-400">成功 / 失败</p>
                  <p className="mt-1 font-semibold text-slate-900 dark:text-white">
                    {user.successCount}
                    <span className="text-slate-300"> / </span>
                    <span className="text-rose-600">{user.failedCount}</span>
                  </p>
                </div>
                <div>
                  <p className="text-xs text-slate-400">活动插件设备</p>
                  <p className="mt-1 font-semibold text-slate-900 dark:text-white">{user.activeDeviceCount}</p>
                </div>
              </CardContent>
            </Card>
          </div>

          <div className="grid gap-4 xl:grid-cols-2">
            {quotaCard("AI 分析额度", user.analysisQuota)}
            {quotaCard("投递额度", user.deliveryQuota)}
          </div>

          <Card>
            <CardHeader>
              <CardTitle>当前周期额度明细</CardTitle>
              <CardDescription>每位用户每月两条：AI 分析与投递</CardDescription>
            </CardHeader>
            <CardContent>
              {quotaRows.length === 0 ? (
                <p className="py-6 text-center text-sm text-slate-400">暂无额度数据</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[640px] border-collapse text-left text-sm">
                    <thead>
                      <tr className="border-b border-slate-200/80 text-xs text-slate-400 dark:border-white/10">
                        <th className="py-2 pr-3 font-medium">资源</th>
                        <th className="py-2 pr-3 font-medium">总量</th>
                        <th className="py-2 pr-3 font-medium">已用</th>
                        <th className="py-2 pr-3 font-medium">预占</th>
                        <th className="py-2 pr-3 font-medium">剩余</th>
                        <th className="py-2 font-medium">重置时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {quotaRows.map((row) => (
                        <tr key={row.quotaId} className="border-b border-slate-100/80 dark:border-white/5">
                          <td className="py-2.5 pr-3">{adminLabel(ADMIN_RESOURCE_LABELS, row.resourceCode)}</td>
                          <td className="py-2.5 pr-3 text-slate-600">{row.total}</td>
                          <td className="py-2.5 pr-3 text-slate-600">{row.used}</td>
                          <td className="py-2.5 pr-3 text-slate-600">{row.reserved}</td>
                          <td className="py-2.5 pr-3 text-emerald-600">{row.remaining}</td>
                          <td className="max-w-[160px] truncate py-2.5 text-slate-500" title={formatDateTime(row.resetAt)}>
                            {formatDateTime(row.resetAt)}
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
              <CardTitle>调整额度</CardTitle>
              <CardDescription>
                调整后立即生效并写入管理员审计；原因必填（最长 {REASON_MAX_LENGTH} 字），只保存在服务端审计中。
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={(event) => void submit(event)} className="space-y-4">
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                  <div className="space-y-2">
                    <Label htmlFor="plan">套餐</Label>
                    <Select
                      id="plan"
                      value={plan}
                      placeholder="选择套餐"
                      onChange={(event) => {
                        setPlan(event.target.value)
                        resetIdempotencyKey()
                      }}
                    >
                      {ADMIN_PLANS.map((item) => (
                        <option key={item} value={item}>
                          {adminLabel(ADMIN_PLAN_LABELS, item)}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="analysisQuotaTotal">AI 分析额度总量</Label>
                    <Input
                      id="analysisQuotaTotal"
                      type="number"
                      min={0}
                      max={1000000}
                      value={analysisTotal}
                      onChange={(event) => {
                        setAnalysisTotal(event.target.value)
                        resetIdempotencyKey()
                      }}
                      placeholder="例如 100"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="deliveryQuotaTotal">投递额度总量</Label>
                    <Input
                      id="deliveryQuotaTotal"
                      type="number"
                      min={0}
                      max={1000000}
                      value={deliveryTotal}
                      onChange={(event) => {
                        setDeliveryTotal(event.target.value)
                        resetIdempotencyKey()
                      }}
                      placeholder="例如 50"
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="reason">调整原因（必填）</Label>
                  <Textarea
                    id="reason"
                    value={reason}
                    maxLength={REASON_MAX_LENGTH}
                    required
                    placeholder="说明调整原因，例如：用户购买月度会员，额度升级"
                    onChange={(event) => {
                      setReason(event.target.value)
                      resetIdempotencyKey()
                    }}
                  />
                </div>
                <div className="flex items-center gap-3">
                  <Button type="submit" disabled={submitting}>
                    {submitting ? <BiLoaderAlt className="animate-spin" /> : null}
                    {submitting ? "提交中…" : "提交调整"}
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        </>
      ) : (
        <Card>
          <CardContent className="space-y-3 pt-5">
            <p className="text-sm text-slate-500">
              {error ? "用户详情加载失败，请返回后台重试。" : "未找到该用户，可能已被删除或没有权限查看。"}
            </p>
            <Button asChild variant="outline" size="sm">
              <Link href="/admin">返回后台</Link>
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
