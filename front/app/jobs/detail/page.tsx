"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { BiAnalyse, BiArrowBack, BiBriefcase, BiLinkExternal, BiLoaderAlt, BiRefresh, BiTask } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  DECISION_BADGE_CLASS,
  DECISION_LABELS,
  MATCH_STATUS_LABELS,
  PLATFORM_LABELS,
  TASK_STATUS_BADGE_CLASS,
  TASK_STATUS_LABELS,
  badgeClassFor,
  formatDateTime,
  formatSalary,
  labelOf,
  newIdempotencyKey,
  type JobDetail,
  type MatchView,
  type QueuedResult,
  type TaskDetailRef,
  type TaskView,
} from "@/lib/cloudTypes"

const SUPPORTED_DELIVERY_PLATFORMS = new Set(["BOSS", "ZHILIAN"])

function emptyMatchStub(jobId: string, result: QueuedResult): MatchView {
  return {
    id: result.matchId, jobId, resumeId: null, preferenceId: null,
    status: result.status, score: null, decision: null, summary: null,
    strengths: [], risks: [], greeting: null, priorityCompany: null,
    model: null, usage: null, error: null, attemptCount: 0,
    createdAt: null, completedAt: null,
  }
}

function taskRefFromView(task: TaskView): TaskDetailRef {
  return {
    id: task.id, status: task.status, greeting: task.greeting,
    version: task.version, confirmationVersion: task.confirmationVersion,
    confirmedAt: task.confirmedAt, createdAt: task.createdAt, finishedAt: null,
  }
}

export default function JobDetailPage() {
  const { secureRequest } = useAuth()
  const [jobId, setJobId] = useState<string | null | undefined>(undefined)
  const [job, setJob] = useState<JobDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [analyzing, setAnalyzing] = useState(false)
  const [creating, setCreating] = useState(false)

  const load = useCallback(async () => {
    if (!jobId) return
    setLoading(true)
    try {
      setJob(await secureRequest<JobDetail>(`/api/jobs/${jobId}`))
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "岗位详情加载失败")
    } finally {
      setLoading(false)
    }
  }, [jobId, secureRequest])

  useEffect(() => {
    setJobId(new URLSearchParams(window.location.search).get("id"))
  }, [])

  useEffect(() => {
    if (jobId === undefined) return
    if (jobId === null) {
      setError("缺少岗位 ID，无法加载岗位详情")
      setLoading(false)
      return
    }
    void load()
  }, [jobId, load])

  const analyze = async (force: boolean) => {
    if (!job) return
    setAnalyzing(true)
    setError("")
    setNotice("")
    try {
      const result = await secureRequest<QueuedResult>(`/api/jobs/${job.id}/analyze`, {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({ force }),
      })
      if (result.status === "SUCCEEDED") {
        // 复用已有分析：直接加载完整结果
        await load()
        setNotice("已复用现有 AI 分析结果")
        return
      }
      const stub = job.latestMatch ?? emptyMatchStub(job.id, result)
      setJob({ ...job, latestMatch: { ...stub, id: result.matchId, status: result.status, error: null } })
      setNotice("AI 分析已进入队列，完成后请手动刷新查看结果")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "AI 分析请求失败")
    } finally {
      setAnalyzing(false)
    }
  }

  const addToDelivery = async () => {
    if (!job?.latestMatch) return
    setCreating(true)
    setError("")
    setNotice("")
    try {
      const task = await secureRequest<TaskView>("/api/delivery/tasks", {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({ jobPostId: job.id, jobMatchId: job.latestMatch.id }),
      })
      setJob({ ...job, deliveryTask: taskRefFromView(task) })
      setNotice("已加入投递清单，请前往投递清单逐条确认")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "加入投递清单失败")
    } finally {
      setCreating(false)
    }
  }

  const match = job?.latestMatch ?? null

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiBriefcase size={28} />}
        title={job?.title ?? "岗位详情"}
        subtitle={job ? `${job.companyName} · ${labelOf(PLATFORM_LABELS, job.platform)}` : "查看岗位采集信息"}
        actions={
          <>
            <Button asChild variant="outline" size="sm"><Link href="/jobs"><BiArrowBack />返回岗位池</Link></Button>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>
          </>
        }
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {notice && <div role="status" className="rounded-lg border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-700">{notice}</div>}

      {loading ? (
        <div className="flex items-center justify-center gap-2 py-24 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载岗位详情…</div>
      ) : job && (
        <div className="space-y-5">
          <div className="grid gap-4 xl:grid-cols-[1fr_320px]">
            <Card>
              <CardHeader>
                <CardTitle>职位描述</CardTitle>
                <CardDescription>采集时间 {formatDateTime(job.capturedAt)}</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="whitespace-pre-wrap text-sm leading-7 text-slate-700">{job.description || "暂无职位描述"}</div>
              </CardContent>
            </Card>
            <div className="space-y-4">
              <Card>
                <CardHeader><CardTitle>基本信息</CardTitle></CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <p><span className="text-slate-400">公司：</span>{job.companyName}</p>
                  <p><span className="text-slate-400">薪资：</span>{formatSalary(job.salary)}</p>
                  <p><span className="text-slate-400">地点：</span>{job.location || "—"}</p>
                  <p><span className="text-slate-400">经验：</span>{job.experience || "—"}</p>
                  <p><span className="text-slate-400">学历：</span>{job.degree || "—"}</p>
                  {job.externalJobId && <p><span className="text-slate-400">平台岗位 ID：</span><span className="break-all">{job.externalJobId}</span></p>}
                  {job.jobUrl ? (
                    <Button asChild className="mt-2 w-full"><a href={job.jobUrl} target="_blank" rel="noreferrer"><BiLinkExternal />打开原岗位</a></Button>
                  ) : (
                    <p className="text-xs text-slate-400">未提供原岗位链接</p>
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>技能与福利</CardTitle></CardHeader>
                <CardContent>
                  <div className="flex flex-wrap gap-2">
                    {[...job.skills, ...job.welfare].length === 0 ? (
                      <span className="text-sm text-slate-500">暂无结构化标签</span>
                    ) : (
                      [...job.skills, ...job.welfare].map((item) => (
                        <span key={item} className="rounded-full bg-blue-50 px-3 py-1 text-xs text-blue-700">{item}</span>
                      ))
                    )}
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><BiAnalyse className="text-blue-600" />AI 匹配分析</CardTitle>
              <CardDescription>匹配结果由后端阈值计算，前端不自行改判</CardDescription>
            </CardHeader>
            <CardContent>
              {!match ? (
                <div className="space-y-3">
                  <p className="text-sm text-slate-500">该岗位尚未进行 AI 匹配分析。</p>
                  <Button onClick={() => void analyze(false)} disabled={analyzing}>
                    {analyzing ? <BiLoaderAlt className="animate-spin" /> : <BiAnalyse />}
                    {analyzing ? "提交中…" : "AI 分析"}
                  </Button>
                </div>
              ) : match.status === "PENDING" || match.status === "PROCESSING" ? (
                <div className="space-y-3">
                  <p className="flex items-center gap-2 text-sm text-slate-600">
                    <BiLoaderAlt className="animate-spin" />
                    匹配{labelOf(MATCH_STATUS_LABELS, match.status)}，分析完成后请手动刷新查看结果。
                  </p>
                  <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>
                </div>
              ) : match.status === "FAILED" ? (
                <div className="space-y-3">
                  <div className="rounded-lg border border-rose-100 bg-rose-50/60 px-4 py-3 text-sm text-rose-700">
                    <p className="font-semibold">匹配分析失败{attemptText(match.attemptCount)}</p>
                    <p className="mt-1">{match.error?.message || "未返回具体失败原因"}{match.error?.code ? `（${match.error.code}）` : ""}</p>
                  </div>
                  <Button onClick={() => void analyze(true)} disabled={analyzing}>
                    {analyzing ? <BiLoaderAlt className="animate-spin" /> : <BiRefresh />}
                    {analyzing ? "提交中…" : "重新分析"}
                  </Button>
                </div>
              ) : (
                <div className="space-y-5">
                  <div className="flex flex-wrap items-center gap-3">
                    {match.score !== null && (
                      <span className="rounded-lg bg-blue-50 px-4 py-2 text-2xl font-bold text-blue-700">{match.score}<span className="ml-1 text-xs font-normal text-blue-500">分</span></span>
                    )}
                    {match.decision && (
                      <span className={`rounded-full px-3 py-1 text-sm ${badgeClassFor(DECISION_BADGE_CLASS, match.decision)}`}>
                        {labelOf(DECISION_LABELS, match.decision)}
                      </span>
                    )}
                    <span className="text-xs text-slate-400">完成于 {formatDateTime(match.completedAt)}</span>
                  </div>
                  {match.summary && (
                    <div>
                      <h3 className="mb-2 text-sm font-semibold text-slate-700">岗位分析</h3>
                      <p className="whitespace-pre-wrap text-sm leading-7 text-slate-700">{match.summary}</p>
                    </div>
                  )}
                  {match.strengths.length > 0 && (
                    <div>
                      <h3 className="mb-2 text-sm font-semibold text-emerald-700">匹配优势</h3>
                      <ul className="space-y-1.5 text-sm text-slate-700">
                        {match.strengths.map((item) => <li key={item} className="flex gap-2"><span className="text-emerald-500">✓</span>{item}</li>)}
                      </ul>
                    </div>
                  )}
                  {match.risks.length > 0 && (
                    <div>
                      <h3 className="mb-2 text-sm font-semibold text-amber-700">风险点</h3>
                      <ul className="space-y-1.5 text-sm text-slate-700">
                        {match.risks.map((item) => <li key={item} className="flex gap-2"><span className="text-amber-500">!</span>{item}</li>)}
                      </ul>
                    </div>
                  )}
                  {match.greeting && (
                    <div>
                      <h3 className="mb-2 text-sm font-semibold text-slate-700">AI 招呼语<span className="ml-2 text-xs font-normal text-slate-400">仅作为建议预览，实际投递前可在投递清单中修改</span></h3>
                      <p className="rounded-lg bg-slate-50 px-4 py-3 text-sm text-slate-700">{match.greeting}</p>
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><BiTask className="text-cyan-600" />投递任务</CardTitle>
              <CardDescription>仅 BOSS 与智联支持云端投递；投递必须逐条确认</CardDescription>
            </CardHeader>
            <CardContent>
              {job.deliveryTask ? (
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className={`rounded-full px-3 py-1 text-sm ${badgeClassFor(TASK_STATUS_BADGE_CLASS, job.deliveryTask.status)}`}>
                      {labelOf(TASK_STATUS_LABELS, job.deliveryTask.status)}
                    </span>
                    {job.deliveryTask.confirmedAt && <span className="text-xs text-slate-400">确认于 {formatDateTime(job.deliveryTask.confirmedAt)}</span>}
                  </div>
                  <Button asChild variant="outline" size="sm"><Link href="/delivery"><BiTask />进入投递清单</Link></Button>
                </div>
              ) : match?.decision === "APPLY" ? (
                <p className="text-sm text-slate-600">
                  该岗位为<strong>推荐投递</strong>，系统完成匹配后会自动加入待确认清单。任务以清单实际数据为准，刷新可查看同步状态。
                </p>
              ) : match?.decision === "SKIP" ? (
                <p className="text-sm text-slate-600">该岗位为<strong>建议跳过</strong>，不会自动创建投递任务。</p>
              ) : match?.decision === "REVIEW" ? (
                <div className="space-y-3">
                  {match.status === "SUCCEEDED" && SUPPORTED_DELIVERY_PLATFORMS.has(job.platform) ? (
                    <>
                      <p className="text-sm text-slate-600">该岗位为<strong>建议复核</strong>，你可以手动加入投递清单并逐条确认。</p>
                      <Button onClick={() => void addToDelivery()} disabled={creating}>
                        {creating ? <BiLoaderAlt className="animate-spin" /> : <BiTask />}
                        {creating ? "正在加入…" : "加入投递清单"}
                      </Button>
                    </>
                  ) : match.status !== "SUCCEEDED" ? (
                    <p className="text-sm text-slate-500">分析完成后，你可以把该岗位手动加入投递清单。</p>
                  ) : (
                    <p className="text-sm text-slate-500">该平台暂不支持云端投递。</p>
                  )}
                </div>
              ) : (
                <p className="text-sm text-slate-400">完成 AI 匹配分析后，这里会展示投递任务状态。</p>
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}

function attemptText(attemptCount: number): string {
  return attemptCount > 0 ? `（已尝试 ${attemptCount} 次）` : ""
}
