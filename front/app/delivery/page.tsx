"use client"

import { FormEvent, useCallback, useEffect, useRef, useState } from "react"
import { BiChevronDown, BiChevronLeft, BiChevronRight, BiChevronUp, BiDevices, BiLoaderAlt, BiRefresh, BiSearch, BiSend, BiTask } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { AuthApiError } from "@/lib/authApi"
import { parseCloudDeliveryEvent, sendCloudDeliveryWake, subscribeChromeBridgeEvents, type CloudDeliveryEvent, type CloudWakeResult } from "@/lib/chromeBridge"
import {
  DECISION_BADGE_CLASS,
  DECISION_LABELS,
  DEVICE_STATUS_LABELS,
  EVENT_TYPE_LABELS,
  PLATFORM_LABELS,
  TASK_STATUS_BADGE_CLASS,
  TASK_STATUS_LABELS,
  badgeClassFor,
  countCodePoints,
  formatDateTime,
  labelOf,
  newIdempotencyKey,
  type BindCodeResult,
  type ConfirmResult,
  type DeviceView,
  type GreetingResult,
  type MatchView,
  type PageResult,
  type RevokeDeviceResult,
  type SkipResult,
  type TaskDetail,
  type TaskListItem,
} from "@/lib/cloudTypes"

const TERMINAL_EVENT_STAGES = new Set(["succeeded", "failed", "paused", "offline"])
const CONFIRMABLE_STATUSES = new Set(["PENDING_CONFIRMATION", "PAUSED"])
const SKIPPABLE_STATUSES = new Set(["PENDING_CONFIRMATION", "CONFIRMED", "PAUSED", "FAILED"])
const READONLY_STATUSES = new Set(["LEASED", "EXECUTING", "SUCCEEDED", "SKIPPED", "CANCELLED"])
const GREETING_MAX_CODE_POINTS = 60
const SKIP_REASON_MAX_LENGTH = 200

const STATUS_GROUPS: { key: string; label: string; statuses: readonly string[]; tone: string }[] = [
  { key: "pending", label: "待确认", statuses: ["PENDING_CONFIRMATION"], tone: "text-amber-600" },
  { key: "confirmed", label: "已确认/执行中", statuses: ["CONFIRMED", "LEASED", "EXECUTING"], tone: "text-sky-600" },
  { key: "attention", label: "需处理", statuses: ["PAUSED", "FAILED"], tone: "text-rose-600" },
  { key: "done", label: "已完成", statuses: ["SUCCEEDED", "SKIPPED", "CANCELLED"], tone: "text-slate-600" },
]

function isRetryableFailed(detail: TaskDetail): boolean {
  return detail.status === "FAILED" && detail.lastError?.retryable === true
}

function isConfirmable(detail: TaskDetail): boolean {
  return CONFIRMABLE_STATUSES.has(detail.status) || isRetryableFailed(detail)
}

function isSkippable(detail: TaskDetail): boolean {
  return SKIPPABLE_STATUSES.has(detail.status)
}

function isGreetingEditable(detail: TaskDetail): boolean {
  if (detail.job.platform !== "BOSS") return false
  if (CONFIRMABLE_STATUSES.has(detail.status) || detail.status === "CONFIRMED") return true
  return isRetryableFailed(detail)
}

function statusGroupFilterEquals(current: string[], group: readonly string[]): boolean {
  if (current.length !== group.length) return false
  return group.every((value) => current.includes(value))
}

export default function DeliveryPage() {
  const { secureRequest } = useAuth()
  const [tasks, setTasks] = useState<PageResult<TaskListItem>>({ items: [], page: 1, size: 20, total: 0, hasNext: false })
  const [page, setPage] = useState(1)
  const [statusFilter, setStatusFilter] = useState<string[]>([])
  const [platform, setPlatform] = useState("")
  const [keyword, setKeyword] = useState("")
  const [submittedKeyword, setSubmittedKeyword] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [cloudEvent, setCloudEvent] = useState<CloudDeliveryEvent | null>(null)

  const loadList = useCallback(async () => {
    setLoading(true)
    const params = new URLSearchParams({ page: String(page), size: "20", sort: "updatedAt,desc" })
    for (const status of statusFilter) params.append("status", status)
    if (platform) params.set("platform", platform)
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
  }, [page, platform, secureRequest, statusFilter, submittedKeyword])

  useEffect(() => {
    void loadList()
  }, [loadList])

  const handleListChanged = useCallback(() => {
    void loadList()
  }, [loadList])

  // 订阅扩展 Cloud 投递事件：只处理含合法 taskId 的事件，无静默轮询。
  // 收到明确阶段时刷新列表；组件卸载必须取消订阅。
  useEffect(() => {
    const unsubscribe = subscribeChromeBridgeEvents((event) => {
      const cloud = parseCloudDeliveryEvent(event.payload)
      if (!cloud) return
      setCloudEvent(cloud)
      if (TERMINAL_EVENT_STAGES.has(cloud.stage)) {
        void loadList()
      }
    })
    return unsubscribe
  }, [loadList])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setPage(1)
    setSubmittedKeyword(keyword.trim())
  }

  const toggleStatusGroup = (statuses: readonly string[]) => {
    setPage(1)
    setStatusFilter((current) => (statusGroupFilterEquals(current, statuses) ? [] : [...statuses]))
  }

  const toggleStatusChip = (status: string) => {
    setPage(1)
    setStatusFilter((current) =>
      current.includes(status) ? current.filter((item) => item !== status) : [...current, status],
    )
  }

  const counts = STATUS_GROUPS.map((group) => ({
    ...group,
    count: tasks.items.filter((task) => group.statuses.includes(task.status)).length,
  }))

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiTask size={28} />}
        title="投递清单"
        subtitle="投递必须逐条确认；插件离线不会丢失已确认任务"
        iconClass="text-cyan-600"
        accentBgClass="bg-cyan-50 dark:bg-cyan-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void loadList()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {counts.map((group) => (
          <button
            key={group.key}
            type="button"
            aria-label={`状态筛选：${group.label}`}
            onClick={() => toggleStatusGroup(group.statuses)}
            className={`rounded-xl border bg-white/90 p-4 text-left shadow-sm transition hover:border-cyan-200 dark:bg-blacksection/70 ${statusGroupFilterEquals(statusFilter, group.statuses) ? "border-cyan-300 ring-2 ring-cyan-100" : "border-slate-200/80"}`}
          >
            <p className={`text-2xl font-bold ${group.tone}`}>{group.count}</p>
            <p className="mt-1 text-sm text-slate-600">{group.label}</p>
          </button>
        ))}
      </div>
      <p className="text-xs text-slate-400">状态概览按当前筛选结果第 {tasks.page} 页统计（本页 {tasks.items.length} 条），点击卡片可快速筛选。</p>

      <Card>
        <CardContent className="pt-5">
          <form className="grid gap-3 md:grid-cols-[1fr_180px_auto]" onSubmit={search}>
            <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索岗位或公司" maxLength={100} aria-label="关键词" />
            <select aria-label="招聘平台" value={platform} onChange={(event) => { setPlatform(event.target.value); setPage(1) }} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部平台</option>
              <option value="BOSS">Boss直聘</option>
              <option value="ZHILIAN">智联招聘</option>
            </select>
            <Button type="submit"><BiSearch />搜索</Button>
          </form>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="text-xs text-slate-400">状态：</span>
            {Object.entries(TASK_STATUS_LABELS).map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => toggleStatusChip(value)}
                className={`rounded-full px-2.5 py-1 text-xs transition ${statusFilter.includes(value) ? "bg-cyan-100 text-cyan-700 ring-1 ring-cyan-300" : "bg-slate-100 text-slate-500 hover:bg-slate-200"}`}
              >
                {label}
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
          <CardDescription>共 {tasks.total} 条；点击任务查看详情，只加载当前任务的完整分析</CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载投递清单…</div>
          ) : tasks.items.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 px-6 py-16 text-center">
              <BiTask className="mx-auto text-4xl text-slate-300" />
              <p className="mt-3 font-semibold text-slate-700">投递清单为空</p>
              <p className="mt-1 text-sm text-slate-500">推荐投递岗位在匹配完成后会自动加入；建议复核岗位可在岗位详情中手动加入。</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs text-slate-500">
                  <tr>
                    <th className="px-3 py-3">岗位</th>
                    <th className="px-3 py-3">平台</th>
                    <th className="px-3 py-3">匹配</th>
                    <th className="px-3 py-3">任务状态</th>
                    <th className="px-3 py-3">设备</th>
                    <th className="px-3 py-3">最近事件</th>
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
                        <p className="mt-1 text-xs text-slate-500">{task.job.companyName}</p>
                      </td>
                      <td className="px-3 py-4 text-slate-600">{labelOf(PLATFORM_LABELS, task.job.platform)}</td>
                      <td className="px-3 py-4">
                        <div className="flex flex-wrap items-center gap-1.5">
                          {task.match.score !== null && <span className="text-sm font-semibold text-slate-700">{task.match.score} 分</span>}
                          {task.match.decision && <span className={`rounded-full px-2 py-0.5 text-xs ${badgeClassFor(DECISION_BADGE_CLASS, task.match.decision)}`}>{labelOf(DECISION_LABELS, task.match.decision)}</span>}
                        </div>
                      </td>
                      <td className="px-3 py-4">
                        <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(TASK_STATUS_BADGE_CLASS, task.status)}`}>{labelOf(TASK_STATUS_LABELS, task.status)}</span>
                      </td>
                      <td className="px-3 py-4 text-xs text-slate-500">{task.device?.deviceName || "任一兼容设备"}</td>
                      <td className="px-3 py-4 text-xs text-slate-500">
                        {task.lastEvent ? `${labelOf(EVENT_TYPE_LABELS, task.lastEvent.eventType)} · ${formatDateTime(task.lastEvent.createdAt)}` : "—"}
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
          cloudEvent={cloudEvent}
          onChanged={handleListChanged}
          onClose={() => setSelectedId(null)}
        />
      )}

      <DeviceManager />
    </div>
  )
}

// ---- 任务详情面板 ----

function TaskDetailPanel({ taskId, cloudEvent, onChanged, onClose }: {
  taskId: string
  cloudEvent: CloudDeliveryEvent | null
  onChanged: () => void
  onClose: () => void
}) {
  const { secureRequest } = useAuth()
  const [detail, setDetail] = useState<TaskDetail | null>(null)
  const [match, setMatch] = useState<MatchView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [warn, setWarn] = useState("")

  // 招呼语编辑
  const [greetingDraft, setGreetingDraft] = useState("")
  const [savingGreeting, setSavingGreeting] = useState(false)
  // 确认面板
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [acknowledged, setAcknowledged] = useState(false)
  const [confirming, setConfirming] = useState(false)
  // 跳过面板
  const [skipOpen, setSkipOpen] = useState(false)
  const [skipReason, setSkipReason] = useState("")
  const [skipAcknowledged, setSkipAcknowledged] = useState(false)
  const [skipping, setSkipping] = useState(false)
  // 唤醒
  const [waking, setWaking] = useState(false)
  const [wakeResult, setWakeResult] = useState<CloudWakeResult | null>(null)
  const [latestEvent, setLatestEvent] = useState<CloudDeliveryEvent | null>(null)
  // 确认面板设备选择
  const [devices, setDevices] = useState<DeviceView[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState("")
  // 已处理的事件指纹：防止终端事件因父组件重渲染触发重复刷新
  const processedEventRef = useRef<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const task = await secureRequest<TaskDetail>(`/api/delivery/tasks/${taskId}`)
      setDetail(task)
      setGreetingDraft(task.greeting ?? "")
      setError("")
      try {
        setMatch(await secureRequest<MatchView>(`/api/jobs/${task.jobPostId}/match`))
      } catch {
        // 完整分析按需读取；缺失不影响任务详情展示
        setMatch(null)
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "任务详情加载失败")
    } finally {
      setLoading(false)
    }
  }, [taskId, secureRequest])

  useEffect(() => {
    void load()
  }, [load])

  // 扩展事件：只处理当前任务的 Cloud 投递事件；明确阶段刷新详情。
  // 同一事件只处理一次，避免父组件重渲染引起重复刷新。
  useEffect(() => {
    if (!cloudEvent || cloudEvent.taskId !== taskId) return
    const fingerprint = `${cloudEvent.stage}:${cloudEvent.code}:${cloudEvent.time ?? ""}`
    if (processedEventRef.current === fingerprint) return
    processedEventRef.current = fingerprint
    setLatestEvent(cloudEvent)
    if (TERMINAL_EVENT_STAGES.has(cloudEvent.stage)) {
      void load()
      onChanged()
    }
  }, [cloudEvent, taskId, load, onChanged])

  const loadDevices = useCallback(async () => {
    try {
      setDevices(await secureRequest<DeviceView[]>("/api/plugin/devices"))
    } catch {
      setDevices([])
    }
  }, [secureRequest])

  const openConfirm = () => {
    setConfirmOpen(true)
    setAcknowledged(false)
    setSelectedDeviceId("")
    setError("")
    void loadDevices()
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
    setWarn("")
    try {
      const result = await secureRequest<ConfirmResult>(`/api/delivery/tasks/${detail.id}/confirm`, {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({
          version: detail.version,
          acknowledged: true,
          assignedDeviceId: selectedDeviceId === "" ? null : selectedDeviceId,
        }),
      })
      // 服务端确认成功即 CONFIRMED：先更新视图，再显式唤醒这一条
      const updated: TaskDetail = {
        ...detail,
        status: result.status,
        confirmationVersion: result.confirmationVersion,
        confirmedAt: result.confirmedAt,
        assignedDeviceId: result.assignedDeviceId,
        version: result.version,
      }
      setDetail(updated)
      setConfirmOpen(false)
      setAcknowledged(false)
      setNotice("任务已确认")
      onChanged()

      setWaking(true)
      const wake = await wakePlugin(detail.id)
      setWaking(false)
      setWakeResult(wake)
      if (wake.accepted) {
        setNotice("任务已确认，插件已接收投递唤醒请求")
      } else {
        // 插件离线/未绑定/忙碌/超时：不回滚确认、不重复 confirm、不自动 skip
        setWarn(`任务已确认，但插件暂未接收：${wake.message}`)
      }
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

  const wakeAgain = async () => {
    if (!detail) return
    setWaking(true)
    setError("")
    setWarn("")
    // 重新唤醒只发 Bridge 消息，不再调用 confirm
    const wake = await wakePlugin(detail.id)
    setWaking(false)
    setWakeResult(wake)
    if (wake.accepted) {
      setNotice("插件已接收投递唤醒请求")
    } else {
      setWarn(`插件仍未接收：${wake.message}`)
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

  const compatibleDevices = devices.filter((device) => device.status === "ACTIVE" && device.capabilities.includes(detail.job.platform))
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
            <span className="ml-2"><a href={detail.job.jobUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">原岗位链接</a></span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
        {notice && <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</div>}
        {warn && <div role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700">{warn}</div>}

        <div className="flex flex-wrap items-center gap-3">
          <span className={`rounded-full px-3 py-1 text-sm ${badgeClassFor(TASK_STATUS_BADGE_CLASS, detail.status)}`}>
            {labelOf(TASK_STATUS_LABELS, detail.status)}
          </span>
          {detail.match.score !== null && <span className="text-sm font-semibold text-slate-700">匹配 {detail.match.score} 分</span>}
          {detail.match.decision && <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(DECISION_BADGE_CLASS, detail.match.decision)}`}>{labelOf(DECISION_LABELS, detail.match.decision)}</span>}
          {detail.confirmedAt && <span className="text-xs text-slate-400">确认于 {formatDateTime(detail.confirmedAt)}</span>}
          {detail.device && <span className="text-xs text-slate-400">设备：{detail.device.deviceName}</span>}
        </div>

        {detail.lastError && (
          <div className="rounded-lg border border-rose-100 bg-rose-50/60 px-4 py-3 text-sm text-rose-700">
            <p className="font-semibold">最近错误{detail.lastError.retryable ? "（可重试）" : ""}</p>
            <p className="mt-1">{detail.lastError.message || "未返回具体错误信息"}{detail.lastError.code ? `（${detail.lastError.code}）` : ""}</p>
          </div>
        )}

        {match?.status === "SUCCEEDED" ? (
          <div className="grid gap-4 lg:grid-cols-2">
            {match.summary && (
              <div className="rounded-lg bg-slate-50 px-4 py-3">
                <h3 className="mb-2 text-sm font-semibold text-slate-700">岗位分析</h3>
                <p className="whitespace-pre-wrap text-sm leading-6 text-slate-700">{match.summary}</p>
              </div>
            )}
            <div className="space-y-4">
              {match.strengths.length > 0 && (
                <div>
                  <h3 className="mb-2 text-sm font-semibold text-emerald-700">匹配优势</h3>
                  <ul className="space-y-1 text-sm text-slate-700">
                    {match.strengths.map((item) => <li key={item} className="flex gap-2"><span className="text-emerald-500">✓</span>{item}</li>)}
                  </ul>
                </div>
              )}
              {match.risks.length > 0 && (
                <div>
                  <h3 className="mb-2 text-sm font-semibold text-amber-700">风险点</h3>
                  <ul className="space-y-1 text-sm text-slate-700">
                    {match.risks.map((item) => <li key={item} className="flex gap-2"><span className="text-amber-500">!</span>{item}</li>)}
                  </ul>
                </div>
              )}
            </div>
          </div>
        ) : (
          <p className="text-sm text-slate-400">该岗位暂无完整 AI 分析，或分析尚未完成。</p>
        )}

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
            <Button onClick={openConfirm}><BiSend />确认并唤醒插件</Button>
          )}
          {isSkippable(detail) && !skipOpen && (
            <Button variant="outline" onClick={() => { setSkipOpen(true); setSkipReason(""); setSkipAcknowledged(false); setError("") }}>跳过任务</Button>
          )}
          {detail.status === "CONFIRMED" && (
            <Button variant="outline" onClick={() => void wakeAgain()} disabled={waking}>
              {waking ? <BiLoaderAlt className="animate-spin" /> : <BiRefresh />}
              {waking ? "唤醒中…" : "重新唤醒插件"}
            </Button>
          )}
          {READONLY_STATUSES.has(detail.status) && (
            <p className="text-xs text-slate-400">
              {detail.status === "LEASED" || detail.status === "EXECUTING"
                ? "任务执行中，为保证租约完整不提供操作，扩展事件会更新进度。"
                : "该任务已结束，只读展示。"}
            </p>
          )}
          {latestEvent && (
            <span className="text-xs text-slate-500">扩展进度：{latestEvent.message || latestEvent.stage}（{formatDateTime(latestEvent.time)}）</span>
          )}
          {wakeResult && !wakeResult.accepted && detail.status === "CONFIRMED" && (
            <span className="text-xs text-amber-600">插件未接收（{wakeResult.code}），可点击“重新唤醒插件”</span>
          )}
        </div>

        {confirmOpen && (
          <div className="space-y-3 rounded-lg border border-cyan-100 bg-cyan-50/40 p-4">
            <h3 className="text-sm font-semibold text-slate-700">逐条确认投递</h3>
            <label className="flex items-start gap-2 text-sm text-slate-700">
              <input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span>我已确认岗位信息与招呼语，同意将该任务交给已绑定的浏览器插件逐条投递。</span>
            </label>
            <label className="block text-sm text-slate-700">
              <span className="mb-1 block text-xs text-slate-500">选择执行设备（可选，留空表示任一兼容设备领取）</span>
              <select aria-label="执行设备" value={selectedDeviceId} onChange={(event) => setSelectedDeviceId(event.target.value)} className="h-10 w-full max-w-md rounded-lg border border-slate-200 bg-white px-3 text-sm">
                <option value="">任一兼容设备（推荐）</option>
                {compatibleDevices.map((device) => (
                  <option key={device.id} value={device.id}>{device.deviceName || device.browserName || "未命名设备"}</option>
                ))}
              </select>
            </label>
            {compatibleDevices.length === 0 && (
              <p className="text-xs text-amber-600">当前没有有效且支持{labelOf(PLATFORM_LABELS, detail.job.platform)}的设备：请先绑定插件，否则任务将等待插件领取后执行。</p>
            )}
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
              <span>确认跳过该任务，跳过后不会投递，且需要重新加入清单才能再次投递。</span>
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

async function wakePlugin(taskId: string): Promise<CloudWakeResult> {
  try {
    return await sendCloudDeliveryWake(taskId)
  } catch {
    return {
      success: false,
      accepted: false,
      taskId,
      code: "BRIDGE_UNAVAILABLE",
      message: "浏览器扩展通信异常，请刷新页面或重新加载扩展后重试",
    }
  }
}

// ---- 插件绑定与设备管理 ----

function DeviceManager() {
  const { secureRequest } = useAuth()
  const [open, setOpen] = useState(false)
  const [devices, setDevices] = useState<DeviceView[]>([])
  const [bindCode, setBindCode] = useState<BindCodeResult | null>(null)
  const [generating, setGenerating] = useState(false)
  const [loadingDevices, setLoadingDevices] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [revoking, setRevoking] = useState<string | null>(null)
  const [confirmRevokeId, setConfirmRevokeId] = useState<string | null>(null)

  const loadDevices = useCallback(async () => {
    setLoadingDevices(true)
    try {
      setDevices(await secureRequest<DeviceView[]>("/api/plugin/devices"))
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "设备列表加载失败")
    } finally {
      setLoadingDevices(false)
    }
  }, [secureRequest])

  const generateCode = async () => {
    setGenerating(true)
    setError("")
    setNotice("")
    try {
      // 绑定码只渲染在页面中：不写 console、localStorage，也不进入 URL
      setBindCode(await secureRequest<BindCodeResult>("/api/plugin/bind-code", {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
      }))
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "绑定码生成失败")
    } finally {
      setGenerating(false)
    }
  }

  const revoke = async (device: DeviceView) => {
    setRevoking(device.id)
    setError("")
    setNotice("")
    try {
      await secureRequest<RevokeDeviceResult>(`/api/plugin/devices/${device.id}/revoke`, {
        method: "POST",
        body: JSON.stringify({ reason: "" }),
      })
      setConfirmRevokeId(null)
      setNotice("设备已撤销")
      await loadDevices()
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "设备撤销失败")
    } finally {
      setRevoking(null)
    }
  }

  const toggleOpen = () => {
    const next = !open
    setOpen(next)
    if (next) void loadDevices()
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <button type="button" onClick={toggleOpen} className="flex w-full items-center justify-between text-left">
            <span className="flex items-center gap-2"><BiDevices className="text-violet-600" />插件绑定与设备管理</span>
            {open ? <BiChevronUp /> : <BiChevronDown />}
          </button>
        </CardTitle>
        <CardDescription>生成一次性绑定码配对浏览器插件；插件 Token 永不在网页展示</CardDescription>
      </CardHeader>
      {open && (
        <CardContent className="space-y-5">
          {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
          {notice && <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</div>}

          <div className="rounded-lg border border-violet-100 bg-violet-50/50 p-4">
            <h3 className="text-sm font-semibold text-slate-700">生成绑定码</h3>
            <p className="mt-1 text-xs text-slate-500">绑定码一次性有效，生成后请在扩展中尽快使用；不会写入浏览器存储或地址栏。</p>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <Button onClick={() => void generateCode()} disabled={generating}>
                {generating ? <BiLoaderAlt className="animate-spin" /> : <BiDevices />}
                {generating ? "生成中…" : "生成一次性绑定码"}
              </Button>
              {bindCode && (
                <div className="flex items-center gap-2">
                  <code className="rounded-lg border border-violet-200 bg-white px-4 py-2 font-mono text-lg tracking-widest text-violet-700">{bindCode.bindCode}</code>
                  <span className="text-xs text-slate-500">剩余 {bindCode.expiresInSeconds} 秒（至 {formatDateTime(bindCode.expiresAt)}）</span>
                </div>
              )}
            </div>
          </div>

          <div>
            <div className="mb-2 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-700">已绑定设备</h3>
              <Button variant="outline" size="sm" onClick={() => void loadDevices()} disabled={loadingDevices}><BiRefresh />刷新设备</Button>
            </div>
            {loadingDevices ? (
              <div className="flex items-center gap-2 py-8 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载设备…</div>
            ) : devices.length === 0 ? (
              <p className="rounded-lg border border-dashed border-slate-300 px-4 py-8 text-center text-sm text-slate-500">尚未绑定任何插件设备。生成绑定码后，在浏览器扩展中完成配对。</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-slate-200 text-xs text-slate-500">
                    <tr>
                      <th className="px-3 py-2">设备</th>
                      <th className="px-3 py-2">浏览器</th>
                      <th className="px-3 py-2">扩展版本</th>
                      <th className="px-3 py-2">能力</th>
                      <th className="px-3 py-2">状态</th>
                      <th className="px-3 py-2">最后在线</th>
                      <th className="px-3 py-2">绑定时间</th>
                      <th className="px-3 py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {devices.map((device) => (
                      <tr key={device.id} className="border-b border-slate-100 last:border-0">
                        <td className="px-3 py-3 text-slate-700">{device.deviceName || "未命名设备"}</td>
                        <td className="px-3 py-3 text-xs text-slate-500">{device.browserName ? `${device.browserName}${device.browserVersion ? ` ${device.browserVersion}` : ""}` : "—"}</td>
                        <td className="px-3 py-3 text-xs text-slate-500">{device.extensionVersion || "—"}</td>
                        <td className="px-3 py-3 text-xs text-slate-500">{device.capabilities.map((item) => labelOf(PLATFORM_LABELS, item)).join("、") || "—"}</td>
                        <td className="px-3 py-3">
                          <span className={`rounded-full px-2.5 py-1 text-xs ${device.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-500"}`}>
                            {labelOf(DEVICE_STATUS_LABELS, device.status)}
                          </span>
                        </td>
                        <td className="px-3 py-3 text-xs text-slate-500">{formatDateTime(device.lastSeenAt)}</td>
                        <td className="px-3 py-3 text-xs text-slate-500">{formatDateTime(device.boundAt)}</td>
                        <td className="px-3 py-3">
                          {device.status === "ACTIVE" && (
                            confirmRevokeId === device.id ? (
                              <div className="flex items-center gap-2">
                                <span className="text-xs text-rose-600">确认撤销该设备？</span>
                                <Button variant="destructive" size="sm" onClick={() => void revoke(device)} disabled={revoking === device.id}>
                                  {revoking === device.id ? <BiLoaderAlt className="animate-spin" /> : null}确定撤销
                                </Button>
                                <Button variant="ghost" size="sm" onClick={() => setConfirmRevokeId(null)}>取消</Button>
                              </div>
                            ) : (
                              <Button variant="outline" size="sm" onClick={() => setConfirmRevokeId(device.id)}>撤销</Button>
                            )
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </CardContent>
      )}
    </Card>
  )
}
