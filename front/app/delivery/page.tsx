"use client"

import { FormEvent, useCallback, useEffect, useState } from "react"
import { BiChevronLeft, BiChevronRight, BiLoaderAlt, BiRefresh, BiSearch, BiSend, BiTask } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { AuthApiError } from "@/lib/authApi"
import { sendCloudDeliveryWake } from "@/lib/chromeBridge"
import {
  DECISION_BADGE_CLASS,
  DECISION_LABELS,
  ERROR_CODE_LABELS,
  EVENT_TYPE_LABELS,
  P6_TASK_STATUSES,
  PLATFORM_LABELS,
  TASK_STATUS_BADGE_CLASS,
  TASK_STATUS_LABELS,
  badgeClassFor,
  countCodePoints,
  formatDateTime,
  formatSalary,
  labelOf,
  newIdempotencyKey,
  type ConfirmResult,
  type DeliverySummary,
  type GreetingResult,
  type PageResult,
  type SkipResult,
  type TaskDetail,
  type TaskListItem,
} from "@/lib/cloudTypes"

const CONFIRMABLE_STATUSES = new Set(["WAITING_CONFIRM", "PAUSED_NEED_USER"])
const SKIPPABLE_STATUSES = new Set([
  "WAITING_CONFIRM", "CONFIRMED", "PULLED_BY_PLUGIN", "RUNNING", "PAUSED_NEED_USER", "FAILED",
])
const GREETING_MAX_CODE_POINTS = 60
const SKIP_REASON_MAX_LENGTH = 200
const EMPTY_SUMMARY: DeliverySummary = {
  waitingConfirm: 0, confirmed: 0, pulledByPlugin: 0, running: 0,
  success: 0, failed: 0, skipped: 0, pausedNeedUser: 0, total: 0,
}

const STATUS_GROUPS: { key: string; label: string; statuses: readonly string[]; tone: string; countKey: keyof DeliverySummary }[] = [
  { key: "waiting", label: "待确认", statuses: ["WAITING_CONFIRM"], tone: "text-amber-600", countKey: "waitingConfirm" },
  { key: "confirmed", label: "已确认", statuses: ["CONFIRMED"], tone: "text-sky-600", countKey: "confirmed" },
  { key: "executing", label: "执行中", statuses: ["PULLED_BY_PLUGIN", "RUNNING"], tone: "text-blue-600", countKey: "pulledByPlugin" },
  { key: "success", label: "投递成功", statuses: ["SUCCESS"], tone: "text-emerald-600", countKey: "success" },
  { key: "failed", label: "投递失败", statuses: ["FAILED"], tone: "text-rose-600", countKey: "failed" },
  { key: "attention", label: "需用户处理", statuses: ["PAUSED_NEED_USER"], tone: "text-orange-600", countKey: "pausedNeedUser" },
  { key: "skipped", label: "已跳过", statuses: ["SKIPPED"], tone: "text-slate-600", countKey: "skipped" },
]

/** 执行中分组同时计两种状态，需要单独合计。 */
function groupCount(summary: DeliverySummary, group: { key: string; countKey: keyof DeliverySummary }): number {
  if (group.key === "executing") return summary.pulledByPlugin + summary.running
  return summary[group.countKey]
}

function isConfirmable(detail: TaskDetail): boolean {
  if (CONFIRMABLE_STATUSES.has(detail.status)) return true
  // 可重试的失败（网络/未知错误）允许重新确认后再执行。
  return detail.status === "FAILED" && detail.lastError?.retryable === true
}

function isSkippable(detail: TaskDetail): boolean {
  return SKIPPABLE_STATUSES.has(detail.status)
}

function isGreetingEditable(detail: TaskDetail): boolean {
  if (detail.job.platform !== "BOSS") return false
  return CONFIRMABLE_STATUSES.has(detail.status) || detail.status === "CONFIRMED"
}

function statusFilterEquals(current: string[], group: readonly string[]): boolean {
  if (current.length !== group.length) return false
  return group.every((value) => current.includes(value))
}

export default function DeliveryPage() {
  const { secureRequest } = useAuth()
  const [tasks, setTasks] = useState<PageResult<TaskListItem>>({ items: [], page: 1, size: 20, total: 0, hasNext: false })
  const [summary, setSummary] = useState<DeliverySummary>(EMPTY_SUMMARY)
  const [page, setPage] = useState(1)
  const [statusFilter, setStatusFilter] = useState<string[]>([])
  const [platform, setPlatform] = useState("")
  const [recommendation, setRecommendation] = useState("")
  const [keyword, setKeyword] = useState("")
  const [submittedKeyword, setSubmittedKeyword] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const loadList = useCallback(async () => {
    setLoading(true)
    const params = new URLSearchParams({ page: String(page), size: "20", sort: "updatedAt,desc" })
    for (const status of statusFilter) params.append("status", status)
    if (platform) params.set("platform", platform)
    if (recommendation) params.set("recommendation", recommendation)
    if (submittedKeyword) params.set("keyword", submittedKeyword)
    try {
      const result = await secureRequest<PageResult<TaskListItem>>(`/api/delivery/tasks?${params.toString()}`)
      setTasks(result)
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "投递清单加载失败")
    } finally {
      setLoading(false)
    }
  }, [page, platform, recommendation, secureRequest, statusFilter, submittedKeyword])

  // 全局统计与列表同口径（平台/关键词/推荐），由服务端聚合，不受分页影响。
  const loadSummary = useCallback(async () => {
    const params = new URLSearchParams()
    if (platform) params.set("platform", platform)
    if (recommendation) params.set("recommendation", recommendation)
    if (submittedKeyword) params.set("keyword", submittedKeyword)
    try {
      setSummary(await secureRequest<DeliverySummary>(`/api/delivery/tasks/summary?${params.toString()}`))
    } catch {
      setSummary(EMPTY_SUMMARY)
    }
  }, [platform, recommendation, secureRequest, submittedKeyword])

  useEffect(() => {
    void loadList()
  }, [loadList])

  useEffect(() => {
    void loadSummary()
  }, [loadSummary])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setPage(1)
    setSubmittedKeyword(keyword.trim())
  }

  const toggleStatusGroup = (statuses: readonly string[]) => {
    setPage(1)
    setStatusFilter((current) => (statusFilterEquals(current, statuses) ? [] : [...statuses]))
  }

  const toggleStatusChip = (status: string) => {
    setPage(1)
    setStatusFilter((current) =>
      current.includes(status) ? current.filter((item) => item !== status) : [...current, status],
    )
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiTask size={28} />}
        title="投递清单"
        subtitle="逐条确认后才进入执行通道；AI 建议仅供参考，不替代你的确认"
        iconClass="text-cyan-600"
        accentBgClass="bg-cyan-50 dark:bg-cyan-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void loadList()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      <div className="rounded-lg border border-sky-100 bg-sky-50/60 px-4 py-3 text-sm text-sky-700">
        逐条确认后，浏览器插件会在你显式开始（或确认成功后的唤醒）时领取并执行；登录失效、验证码、风控等需要人工处理的情况会暂停并提示你处理，绝不绕过验证或自动重试。
      </div>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {STATUS_GROUPS.map((group) => (
          <button
            key={group.key}
            type="button"
            aria-label={`状态筛选：${group.label}`}
            onClick={() => toggleStatusGroup(group.statuses)}
            className={`rounded-xl border bg-white/90 p-4 text-left shadow-sm transition hover:border-cyan-200 dark:bg-blacksection/70 ${statusFilterEquals(statusFilter, group.statuses) ? "border-cyan-300 ring-2 ring-cyan-100" : "border-slate-200/80"}`}
          >
            <p className={`text-2xl font-bold ${group.tone}`}>{groupCount(summary, group)}</p>
            <p className="mt-1 text-sm text-slate-600">{group.label}</p>
          </button>
        ))}
      </div>
      <p className="text-xs text-slate-400">
        状态概览为全局统计（共 {summary.total} 条），与上方平台/关键词/推荐筛选同口径，不受分页影响；点击卡片可快速筛选。
      </p>

      <Card>
        <CardContent className="pt-5">
          <form className="grid gap-3 md:grid-cols-[1fr_170px_170px_auto]" onSubmit={search}>
            <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索岗位或公司" maxLength={100} aria-label="关键词" />
            <select aria-label="招聘平台" value={platform} onChange={(event) => { setPlatform(event.target.value); setPage(1) }} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部平台</option>
              <option value="BOSS">Boss直聘</option>
              <option value="ZHILIAN">智联招聘</option>
            </select>
            <select aria-label="AI 推荐" value={recommendation} onChange={(event) => { setRecommendation(event.target.value); setPage(1) }} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部推荐</option>
              <option value="APPLY">推荐投递</option>
              <option value="REVIEW">谨慎投递</option>
              <option value="SKIP">不建议投递</option>
            </select>
            <Button type="submit"><BiSearch />搜索</Button>
          </form>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="text-xs text-slate-400">状态：</span>
            {P6_TASK_STATUSES.map((value) => (
              <button
                key={value}
                type="button"
                onClick={() => toggleStatusChip(value)}
                className={`rounded-full px-2.5 py-1 text-xs transition ${statusFilter.includes(value) ? "bg-cyan-100 text-cyan-700 ring-1 ring-cyan-300" : "bg-slate-100 text-slate-500 hover:bg-slate-200"}`}
              >
                {labelOf(TASK_STATUS_LABELS, value)}
              </button>
            ))}
            {statusFilter.length > 0 && (
              <button type="button" onClick={() => { setPage(1); setStatusFilter([]) }} className="text-xs text-blue-600 hover:underline">清除状态筛选</button>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>投递任务</CardTitle>
          <CardDescription>共 {tasks.total} 条；点击任务查看 AI 分析与完整事件时间线</CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载投递清单…</div>
          ) : tasks.items.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 px-6 py-16 text-center">
              <BiTask className="mx-auto text-4xl text-slate-300" />
              <p className="mt-3 font-semibold text-slate-700">投递清单为空</p>
              <p className="mt-1 text-sm text-slate-500">推荐投递岗位在匹配完成后会自动加入；谨慎投递/不建议投递的岗位可在岗位详情中手动加入。</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs text-slate-500">
                  <tr>
                    <th className="px-3 py-3">岗位</th>
                    <th className="px-3 py-3">平台</th>
                    <th className="px-3 py-3">薪资</th>
                    <th className="px-3 py-3">AI 推荐</th>
                    <th className="px-3 py-3">推荐理由</th>
                    <th className="px-3 py-3">招呼语</th>
                    <th className="px-3 py-3">任务状态</th>
                    <th className="px-3 py-3">更新时间</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.items.map((task) => (
                    <tr
                      key={task.id}
                      className={`cursor-pointer border-b border-slate-100 last:border-0 hover:bg-slate-50/70 ${selectedId === task.id ? "bg-cyan-50/50" : ""}`}
                      onClick={() => setSelectedId(selectedId === task.id ? null : task.id)}
                    >
                      <td className="px-3 py-4">
                        <button type="button" onClick={() => setSelectedId(selectedId === task.id ? null : task.id)} className="text-left font-semibold text-blue-600 hover:underline">
                          {task.job.title}
                        </button>
                        <p className="mt-1 text-xs text-slate-500">{task.job.companyName}{task.job.location ? ` · ${task.job.location}` : ""}</p>
                      </td>
                      <td className="px-3 py-4 text-slate-600">{labelOf(PLATFORM_LABELS, task.job.platform)}</td>
                      <td className="px-3 py-4 text-xs text-slate-500">{formatSalary(task.job.salary)}</td>
                      <td className="px-3 py-4">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {task.match?.score != null && <span className="text-sm font-semibold text-slate-700">{task.match.score} 分</span>}
                          {task.match?.decision && <span className={`rounded-full px-2 py-0.5 text-xs ${badgeClassFor(DECISION_BADGE_CLASS, task.match.decision)}`}>{labelOf(DECISION_LABELS, task.match.decision)}</span>}
                        </div>
                      </td>
                      <td className="max-w-[260px] px-3 py-4 text-xs text-slate-500">
                        <p className="line-clamp-2">{task.match?.summary || "—"}</p>
                      </td>
                      <td className="max-w-[160px] px-3 py-4 text-xs text-slate-500">
                        <p className="truncate">{task.greeting || "—"}</p>
                      </td>
                      <td className="px-3 py-4">
                        <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(TASK_STATUS_BADGE_CLASS, task.status)}`}>{labelOf(TASK_STATUS_LABELS, task.status)}</span>
                      </td>
                      <td className="px-3 py-4 text-xs text-slate-500">{formatDateTime(task.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-4">
            <p className="text-xs text-slate-500">第 {tasks.page} 页</p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page <= 1 || loading} onClick={() => setPage((value) => Math.max(1, value - 1))}><BiChevronLeft />上一页</Button>
              <Button variant="outline" size="sm" disabled={!tasks.hasNext || loading} onClick={() => setPage((value) => value + 1)}>下一页<BiChevronRight /></Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {selectedId && (
        <TaskDetailPanel
          taskId={selectedId}
          onChanged={() => { void loadList(); void loadSummary() }}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  )
}

// ---- 任务详情面板 ----

function TaskDetailPanel({ taskId, onChanged, onClose }: {
  taskId: string
  onChanged: () => void
  onClose: () => void
}) {
  const { secureRequest } = useAuth()
  const [detail, setDetail] = useState<TaskDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")

  // 招呼语编辑
  const [greetingDraft, setGreetingDraft] = useState("")
  const [savingGreeting, setSavingGreeting] = useState(false)
  // 确认面板（逐条确认，不选择设备）
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [acknowledged, setAcknowledged] = useState(false)
  const [confirming, setConfirming] = useState(false)
  // 跳过面板
  const [skipOpen, setSkipOpen] = useState(false)
  const [skipReason, setSkipReason] = useState("")
  const [skipAcknowledged, setSkipAcknowledged] = useState(false)
  const [skipping, setSkipping] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const task = await secureRequest<TaskDetail>(`/api/delivery/tasks/${taskId}`)
      setDetail(task)
      setGreetingDraft(task.greeting ?? "")
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "任务详情加载失败")
    } finally {
      setLoading(false)
    }
  }, [taskId, secureRequest])

  useEffect(() => {
    void load()
  }, [load])

  const openConfirm = () => {
    setConfirmOpen(true)
    setAcknowledged(false)
    setError("")
  }

  const saveGreeting = async () => {
    if (!detail || detail.job.platform !== "BOSS") return
    setSavingGreeting(true)
    setError("")
    setNotice("")
    try {
      const result = await secureRequest<GreetingResult>(`/api/delivery/tasks/${detail.id}/greeting`, {
        method: "PUT",
        body: JSON.stringify({ version: detail.version, greeting: greetingDraft }),
      })
      setDetail({
        ...detail,
        greeting: result.greeting,
        status: result.status,
        version: result.version,
        ...(result.confirmationRequired ? {
          confirmedAt: null,
          assignedDeviceId: null,
          device: null,
          startedAt: null,
        } : {}),
      })
      if (result.confirmationRequired) {
        setNotice("招呼语已保存。已确认内容发生变化，需要重新确认投递。")
      } else {
        setNotice("招呼语已保存。")
      }
    } catch (requestError) {
      if (isVersionConflict(requestError)) {
        setError("任务数据已变化，已重新加载当前任务，请确认后重试")
        await load()
      } else {
        setError(requestError instanceof Error ? requestError.message : "招呼语保存失败")
      }
    } finally {
      setSavingGreeting(false)
    }
  }

  const confirmTask = async () => {
    if (!detail) return
    setConfirming(true)
    setError("")
    setNotice("")
    try {
      const result = await secureRequest<ConfirmResult>(`/api/delivery/tasks/${detail.id}/confirm`, {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({
          version: detail.version,
          acknowledged: true,
        }),
      })
      setDetail({
        ...detail,
        status: result.status,
        confirmationVersion: result.confirmationVersion,
        confirmedAt: result.confirmedAt,
        assignedDeviceId: result.assignedDeviceId,
        version: result.version,
      })
      setConfirmOpen(false)
      setAcknowledged(false)
      // 确认成功后显式唤醒插件（同一 Idempotency-Key 内的动作只发生一次）。
      // 唤醒结果只用于提示：插件未安装/未绑定时任务保持已确认，绝不静默重试。
      // 唤醒等待上限 3 秒，插件未安装时不影响确认完成。
      const wake = await sendCloudDeliveryWake(detail.id, 3000)
      if (wake.accepted) {
        setNotice(`任务已确认，插件已接收执行请求（${wake.state === "queued" ? "已进入执行队列" : "正在执行"}）。`)
      } else {
        setNotice("任务已确认。插件暂未接收执行请求（" + wake.message + "），可在扩展 popup 中「开始执行」或稍后重新确认唤醒。")
      }
      onChanged()
      await load()
    } catch (requestError) {
      if (isVersionConflict(requestError)) {
        setError("任务数据已变化，已重新加载当前任务，请确认后重试")
        await load()
      } else {
        setError(requestError instanceof Error ? requestError.message : "确认投递失败")
      }
    } finally {
      setConfirming(false)
    }
  }

  const skipTask = async () => {
    if (!detail) return
    setSkipping(true)
    setError("")
    setNotice("")
    try {
      const result = await secureRequest<SkipResult>(`/api/delivery/tasks/${detail.id}/skip`, {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({
          version: detail.version,
          reason: skipReason.trim() === "" ? null : skipReason.trim(),
        }),
      })
      setDetail({ ...detail, status: result.status, finishedAt: result.finishedAt, version: result.version })
      setSkipOpen(false)
      setSkipReason("")
      setSkipAcknowledged(false)
      setNotice("任务已跳过")
      onChanged()
    } catch (requestError) {
      if (isVersionConflict(requestError)) {
        setError("任务数据已变化，已重新加载当前任务，请确认后重试")
        await load()
      } else {
        setError(requestError instanceof Error ? requestError.message : "跳过任务失败")
      }
    } finally {
      setSkipping(false)
    }
  }

  if (loading && !detail) {
    return <Card><CardContent><div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载任务详情…</div></CardContent></Card>
  }

  if (!detail) {
    return (
      <Card>
        <CardContent className="space-y-3 pt-5">
          {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
          <Button variant="outline" size="sm" onClick={onClose}>关闭详情</Button>
        </CardContent>
      </Card>
    )
  }

  const greetingCodePoints = countCodePoints(greetingDraft)
  const greetingOverLimit = greetingCodePoints > GREETING_MAX_CODE_POINTS

  return (
    <Card className="border-cyan-200">
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-3">
          <span className="flex items-center gap-2">
            <BiTask className="text-cyan-600" />
            任务详情 · {detail.job.title}
          </span>
          <Button variant="ghost" size="sm" onClick={onClose}>关闭</Button>
        </CardTitle>
        <CardDescription>
          {detail.job.companyName} · {labelOf(PLATFORM_LABELS, detail.job.platform)} · 第 {detail.version} 版
          {detail.job.jobUrl && (
            <span className="ml-2"><a href={detail.job.jobUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">查看岗位详情</a></span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
        {notice && <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</div>}

        <div className="flex flex-wrap items-center gap-3">
          <span className={`rounded-full px-3 py-1 text-sm ${badgeClassFor(TASK_STATUS_BADGE_CLASS, detail.status)}`}>
            {labelOf(TASK_STATUS_LABELS, detail.status)}
          </span>
          {detail.match?.score != null && <span className="text-sm font-semibold text-slate-700">匹配 {detail.match.score} 分</span>}
          {detail.match?.decision && <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(DECISION_BADGE_CLASS, detail.match.decision)}`}>{labelOf(DECISION_LABELS, detail.match.decision)}</span>}
          {detail.confirmedAt && <span className="text-xs text-slate-400">确认于 {formatDateTime(detail.confirmedAt)}</span>}
          {detail.startedAt && <span className="text-xs text-slate-400">开始于 {formatDateTime(detail.startedAt)}</span>}
          {detail.finishedAt && <span className="text-xs text-slate-400">结束于 {formatDateTime(detail.finishedAt)}</span>}
          {detail.attemptCount > 0 && <span className="text-xs text-slate-400">已尝试 {detail.attemptCount} 次</span>}
        </div>

        {detail.lastError?.code && (
          <div className="rounded-lg border border-rose-100 bg-rose-50/60 px-4 py-3 text-sm text-rose-700">
            <span className="font-semibold">{labelOf(ERROR_CODE_LABELS, detail.lastError.code)}</span>
            {detail.lastError.message && <p className="mt-1 text-xs">{detail.lastError.message}</p>}
            {detail.lastError.retryable === true && detail.status === "FAILED" && (
              <p className="mt-1 text-xs">该失败可重试：处理页面问题后可重新确认投递。</p>
            )}
          </div>
        )}

        <div className="grid gap-4 lg:grid-cols-2">
          {detail.match?.summary ? (
            <div className="rounded-lg bg-slate-50 px-4 py-3">
              <h3 className="mb-2 text-sm font-semibold text-slate-700">AI 推荐理由</h3>
              <p className="whitespace-pre-wrap text-sm leading-6 text-slate-700">{detail.match.summary}</p>
            </div>
          ) : null}
          <div className="space-y-4">
            {(detail.match?.strengths.length ?? 0) > 0 && (
              <div>
                <h3 className="mb-2 text-sm font-semibold text-emerald-700">简历命中点</h3>
                <ul className="space-y-1 text-sm text-slate-700">
                  {detail.match!.strengths.map((item) => <li key={item} className="flex gap-2"><span className="text-emerald-500">✓</span>{item}</li>)}
                </ul>
              </div>
            )}
            {(detail.match?.risks.length ?? 0) > 0 && (
              <div>
                <h3 className="mb-2 text-sm font-semibold text-amber-700">风险点</h3>
                <ul className="space-y-1 text-sm text-slate-700">
                  {detail.match!.risks.map((item) => <li key={item} className="flex gap-2"><span className="text-amber-500">!</span>{item}</li>)}
                </ul>
              </div>
            )}
            {(detail.match?.strengths.length ?? 0) === 0 && (detail.match?.risks.length ?? 0) === 0 && !detail.match?.summary && (
              <p className="text-sm text-slate-400">该任务暂无 AI 推荐理由。</p>
            )}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-700">
            招呼语
            {detail.job.platform === "BOSS" && isGreetingEditable(detail) && <span className="ml-2 text-xs font-normal text-slate-400">修改已确认内容会重新要求确认</span>}
          </h3>
          {detail.job.platform === "ZHILIAN" ? (
            <div>
              <Textarea value="" placeholder="智联投递不使用自定义招呼语" disabled aria-label="招呼语" />
              <p className="mt-1 text-xs text-slate-400">智联投递不使用自定义招呼语，编辑不可用。</p>
            </div>
          ) : (
            <div className="space-y-2">
              <Textarea
                value={greetingDraft}
                onChange={(event) => setGreetingDraft(event.target.value)}
                disabled={!isGreetingEditable(detail) || savingGreeting}
                placeholder={detail.greeting ? "" : "暂无招呼语"}
                aria-label="招呼语"
              />
              {isGreetingEditable(detail) && (
                <div className="flex items-center justify-between gap-3">
                  <p className={`text-xs ${greetingOverLimit ? "text-rose-600" : "text-slate-400"}`}>
                    {greetingCodePoints}/{GREETING_MAX_CODE_POINTS} 字符（服务端仍为最终校验）
                  </p>
                  <Button size="sm" onClick={() => void saveGreeting()} disabled={savingGreeting || greetingOverLimit}>
                    {savingGreeting ? <BiLoaderAlt className="animate-spin" /> : <BiSend />}
                    {savingGreeting ? "保存中…" : "保存招呼语"}
                  </Button>
                </div>
              )}
              {!isGreetingEditable(detail) && detail.job.platform === "BOSS" && (
                <p className="text-xs text-slate-400">当前状态不允许修改招呼语。</p>
              )}
            </div>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {isConfirmable(detail) && !confirmOpen && (
            <Button onClick={openConfirm}><BiSend />确认投递</Button>
          )}
          {isSkippable(detail) && !skipOpen && (
            <Button variant="outline" onClick={() => { setSkipOpen(true); setSkipReason(""); setSkipAcknowledged(false); setError("") }}>跳过任务</Button>
          )}
          {detail.status === "CONFIRMED" && (
            <p className="text-xs text-slate-400">任务已确认，等待插件领取执行；也可在扩展 popup 中点击「开始执行」。</p>
          )}
          {detail.status === "PULLED_BY_PLUGIN" && (
            <p className="text-xs text-slate-400">插件已领取该任务，即将开始执行。</p>
          )}
          {detail.status === "RUNNING" && (
            <p className="text-xs text-blue-500">插件正在招聘平台页面执行投递，请在浏览器中保持该页面。</p>
          )}
          {detail.status === "PAUSED_NEED_USER" && (
            <p className="text-xs text-rose-600">
              执行已暂停，需要你处理：{labelOf(ERROR_CODE_LABELS, detail.lastError?.code)}。
              请按提示登录、完成验证或确认页面状态后，重新「确认投递」恢复执行；插件绝不绕过验证或自动登录。
            </p>
          )}
          {detail.status === "SUCCESS" && (
            <p className="text-xs text-emerald-600">该任务已投递成功，只读展示。</p>
          )}
          {detail.status === "SKIPPED" && (
            <p className="text-xs text-slate-400">该任务已跳过，只读展示。</p>
          )}
        </div>

        {confirmOpen && (
          <div className="space-y-3 rounded-lg border border-cyan-100 bg-cyan-50/40 p-4">
            <h3 className="text-sm font-semibold text-slate-700">逐条确认投递</h3>
            <label className="flex items-start gap-2 text-sm text-slate-700">
              <input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span>我已确认岗位信息与招呼语，同意插件在我显式开始后自动执行投递；遇到登录、验证码或风控时插件会暂停等待我处理，绝不绕过验证。</span>
            </label>
            <div className="flex gap-2">
              <Button onClick={() => void confirmTask()} disabled={!acknowledged || confirming}>
                {confirming ? <BiLoaderAlt className="animate-spin" /> : <BiSend />}
                {confirming ? "确认中…" : "确认投递"}
              </Button>
              <Button variant="ghost" onClick={() => setConfirmOpen(false)} disabled={confirming}>取消</Button>
            </div>
          </div>
        )}

        {skipOpen && (
          <div className="space-y-3 rounded-lg border border-slate-200 bg-slate-50/60 p-4">
            <h3 className="text-sm font-semibold text-slate-700">跳过任务（逐条操作）</h3>
            <label className="block text-sm text-slate-700">
              <span className="mb-1 block text-xs text-slate-500">跳过原因（可选，不超过 {SKIP_REASON_MAX_LENGTH} 字）</span>
              <Textarea value={skipReason} onChange={(event) => setSkipReason(event.target.value)} maxLength={SKIP_REASON_MAX_LENGTH} placeholder="例如：岗位要求与简历不符" aria-label="跳过原因" />
            </label>
            <label className="flex items-start gap-2 text-sm text-slate-700">
              <input type="checkbox" checked={skipAcknowledged} onChange={(event) => setSkipAcknowledged(event.target.checked)} />
              <span>确认跳过该任务，跳过后不会投递该岗位。</span>
            </label>
            <div className="flex gap-2">
              <Button variant="destructive" onClick={() => void skipTask()} disabled={!skipAcknowledged || skipping}>
                {skipping ? <BiLoaderAlt className="animate-spin" /> : <BiTask />}
                {skipping ? "跳过中…" : "确认跳过"}
              </Button>
              <Button variant="ghost" onClick={() => setSkipOpen(false)} disabled={skipping}>取消</Button>
            </div>
          </div>
        )}

        {detail.events.length > 0 && (
          <div>
            <h3 className="mb-2 text-sm font-semibold text-slate-700">事件时间线</h3>
            <ul className="space-y-1.5 text-xs text-slate-500">
              {[...detail.events].reverse().map((event) => (
                <li key={event.id} className="flex flex-wrap gap-2">
                  <span className="text-slate-600">{labelOf(EVENT_TYPE_LABELS, event.eventType)}</span>
                  {event.toStatus && <span>→ {labelOf(TASK_STATUS_LABELS, event.toStatus)}</span>}
                  <span>· {formatDateTime(event.createdAt)}</span>
                  {event.actorType && <span>· {event.actorType === "USER" ? "用户操作" : "系统"}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function isVersionConflict(error: unknown): boolean {
  return error instanceof AuthApiError && (error.code === "RESOURCE_VERSION_CONFLICT" || error.status === 409)
}
