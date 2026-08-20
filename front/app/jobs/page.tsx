"use client"

import Link from "next/link"
import { FormEvent, useCallback, useEffect, useState } from "react"
import { BiAnalyse, BiBriefcase, BiChevronLeft, BiChevronRight, BiLoaderAlt, BiRefresh, BiSearch } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import {
  DECISION_BADGE_CLASS,
  DECISION_LABELS,
  JOB_STATUS_LABELS,
  MATCH_STATUS_BADGE_CLASS,
  MATCH_STATUS_LABELS,
  PLATFORM_LABELS,
  TASK_STATUS_LABELS,
  badgeClassFor,
  formatDateTime,
  formatSalary,
  labelOf,
  newIdempotencyKey,
  type JobSummary,
  type MatchSummary,
  type PageResult,
  type QueuedResult,
} from "@/lib/cloudTypes"

function mergeAnalyzeResult(previous: MatchSummary | null, result: QueuedResult): MatchSummary {
  const base: MatchSummary = previous ?? {
    id: result.matchId, score: null, decision: null, greeting: null,
    status: result.status, completedAt: null,
  }
  // 复用现有分析时保留原分数/结论；重新排队后旧结果失效
  if (result.reusedExisting) return { ...base, id: result.matchId, status: result.status }
  return {
    id: result.matchId, score: null, decision: null, greeting: null,
    status: result.status, completedAt: null,
  }
}

function MatchCell({ match, analyzing, onAnalyze }: { match: MatchSummary | null; analyzing: boolean; onAnalyze: () => void }) {
  if (!match) {
    return (
      <div className="flex items-center gap-2">
        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-500">未分析</span>
        <Button variant="outline" size="sm" onClick={onAnalyze} disabled={analyzing}>
          {analyzing ? <BiLoaderAlt className="animate-spin" /> : <BiAnalyse />}
          {analyzing ? "提交中…" : "AI 分析"}
        </Button>
      </div>
    )
  }
  const queued = match.status === "PENDING" || match.status === "PROCESSING"
  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(MATCH_STATUS_BADGE_CLASS, match.status)}`}>
          {labelOf(MATCH_STATUS_LABELS, match.status)}
        </span>
        {match.score !== null && <span className="text-sm font-semibold text-slate-700">{match.score} 分</span>}
        {match.decision && (
          <span className={`rounded-full px-2.5 py-1 text-xs ${badgeClassFor(DECISION_BADGE_CLASS, match.decision)}`}>
            {labelOf(DECISION_LABELS, match.decision)}
          </span>
        )}
      </div>
      <div>
        {match.status === "SUCCEEDED" ? (
          <span className="text-xs text-slate-400">完整分析见岗位详情</span>
        ) : match.status === "FAILED" ? (
          <Button variant="outline" size="sm" onClick={onAnalyze} disabled={analyzing}>
            {analyzing ? <BiLoaderAlt className="animate-spin" /> : <BiRefresh />}
            {analyzing ? "提交中…" : "重新分析"}
          </Button>
        ) : queued ? (
          <span className="text-xs text-slate-400">分析排队中，请稍后手动刷新</span>
        ) : null}
      </div>
    </div>
  )
}

function TaskCell({ task }: { task: JobSummary["deliveryTaskStatus"] }) {
  if (!task) return <span className="text-xs text-slate-400">—</span>
  return (
    <Link href="/delivery" className="inline-flex items-center gap-1.5">
      <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-600">{labelOf(TASK_STATUS_LABELS, task.status)}</span>
    </Link>
  )
}

export default function JobsPage() {
  const { secureRequest } = useAuth()
  const [jobs, setJobs] = useState<PageResult<JobSummary>>({ items: [], page: 1, size: 20, total: 0, hasNext: false })
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState("")
  const [submittedKeyword, setSubmittedKeyword] = useState("")
  const [platform, setPlatform] = useState("")
  const [status, setStatus] = useState("ACTIVE")
  const [matchStatus, setMatchStatus] = useState("")
  const [matchDecision, setMatchDecision] = useState("")
  const [minScore, setMinScore] = useState("")
  const [submittedMinScore, setSubmittedMinScore] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [analyzing, setAnalyzing] = useState<Record<string, boolean>>({})

  const load = useCallback(async () => {
    setLoading(true)
    const params = new URLSearchParams({ page: String(page), size: "20", sort: "lastSeenAt,desc" })
    if (submittedKeyword) params.set("keyword", submittedKeyword)
    if (platform) params.set("platform", platform)
    if (status) params.set("status", status)
    if (matchStatus) params.set("matchStatus", matchStatus)
    if (matchDecision) params.set("matchDecision", matchDecision)
    if (submittedMinScore && !Number.isNaN(Number(submittedMinScore))) params.set("minScore", submittedMinScore)
    try {
      const result = await secureRequest<PageResult<JobSummary>>(`/api/jobs?${params.toString()}`)
      setJobs(result)
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "岗位池加载失败")
    } finally {
      setLoading(false)
    }
  }, [page, platform, secureRequest, status, submittedKeyword, matchStatus, matchDecision, submittedMinScore])

  useEffect(() => {
    void load()
  }, [load])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setPage(1)
    setSubmittedKeyword(keyword.trim())
    setSubmittedMinScore(minScore.trim())
  }

  const resetToFirstPage = (apply: () => void) => {
    setPage(1)
    apply()
  }

  const analyze = async (job: JobSummary) => {
    const jobId = job.id
    setAnalyzing((current) => ({ ...current, [jobId]: true }))
    setError("")
    setNotice("")
    try {
      const isFailed = job.latestMatchSummary?.status === "FAILED"
      // 每次点击都是一次新的用户动作，生成新的幂等键；网络重试由 secureRequest 处理
      const result = await secureRequest<QueuedResult>(`/api/jobs/${jobId}/analyze`, {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
        body: JSON.stringify({ force: isFailed }),
      })
      setJobs((current) => ({
        ...current,
        items: current.items.map((item) =>
          item.id === jobId ? { ...item, latestMatchSummary: mergeAnalyzeResult(item.latestMatchSummary, result) } : item,
        ),
      }))
      setNotice(result.reusedExisting
        ? "已复用现有 AI 分析结果，可刷新查看"
        : "AI 分析已进入队列，完成后请手动刷新查看结果")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "AI 分析请求失败")
    } finally {
      setAnalyzing((current) => ({ ...current, [jobId]: false }))
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiBriefcase size={28} />}
        title="岗位池"
        subtitle="插件采集的岗位与 AI 匹配摘要集中展示；完整岗位分析请进入详情查看"
        iconClass="text-blue-600"
        accentBgClass="bg-blue-50 dark:bg-blue-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {notice && <div role="status" className="rounded-lg border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-700">{notice}</div>}

      <Card>
        <CardContent className="pt-5">
          <form className="grid gap-3 md:grid-cols-3 xl:grid-cols-[1fr_150px_130px_150px_150px_90px_auto]" onSubmit={search}>
            <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索职位、公司或城市" maxLength={100} aria-label="关键词" />
            <select aria-label="招聘平台" value={platform} onChange={(event) => resetToFirstPage(() => setPlatform(event.target.value))} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部平台</option>
              {Object.entries(PLATFORM_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <select aria-label="岗位状态" value={status} onChange={(event) => resetToFirstPage(() => setStatus(event.target.value))} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部状态</option>
              {Object.entries(JOB_STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <select aria-label="匹配状态" value={matchStatus} onChange={(event) => resetToFirstPage(() => setMatchStatus(event.target.value))} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">匹配状态不限</option>
              {Object.entries(MATCH_STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <select aria-label="推荐等级" value={matchDecision} onChange={(event) => resetToFirstPage(() => setMatchDecision(event.target.value))} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">推荐等级不限</option>
              {Object.entries(DECISION_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <Input aria-label="最低匹配分" type="number" min="0" max="100" value={minScore} onChange={(event) => setMinScore(event.target.value)} placeholder="最低分" className="h-10" />
            <Button type="submit"><BiSearch />搜索</Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>岗位列表</CardTitle>
          <CardDescription>共 {jobs.total} 条，仅显示当前用户数据</CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载岗位…</div>
          ) : jobs.items.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 px-6 py-16 text-center">
              <BiBriefcase className="mx-auto text-4xl text-slate-300" />
              <p className="mt-3 font-semibold text-slate-700">岗位池目前为空</p>
              <p className="mt-1 text-sm text-slate-500">绑定浏览器插件后，采集的岗位会自动出现在这里；也可以调整上方筛选条件。</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs text-slate-500">
                  <tr>
                    <th className="px-3 py-3">岗位</th>
                    <th className="px-3 py-3">薪资 / 地点</th>
                    <th className="px-3 py-3">平台</th>
                    <th className="px-3 py-3">AI 匹配</th>
                    <th className="px-3 py-3">投递任务</th>
                    <th className="px-3 py-3">最近采集</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.items.map((job) => (
                    <tr key={job.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50/70">
                      <td className="px-3 py-4">
                        <Link href={`/jobs/detail?id=${encodeURIComponent(job.id)}`} className="font-semibold text-blue-600 hover:underline">{job.title}</Link>
                        <p className="mt-1 text-xs text-slate-500">{job.companyName}</p>
                      </td>
                      <td className="px-3 py-4">
                        <p className="text-slate-700">{formatSalary(job.salary)}</p>
                        <p className="mt-1 text-xs text-slate-500">{job.location || "—"}</p>
                      </td>
                      <td className="px-3 py-4 text-slate-600">{labelOf(PLATFORM_LABELS, job.platform)}</td>
                      <td className="px-3 py-4">
                        <MatchCell match={job.latestMatchSummary} analyzing={!!analyzing[job.id]} onAnalyze={() => void analyze(job)} />
                      </td>
                      <td className="px-3 py-4"><TaskCell task={job.deliveryTaskStatus} /></td>
                      <td className="px-3 py-4 text-xs text-slate-500">{formatDateTime(job.lastSeenAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-4">
            <p className="text-xs text-slate-500">第 {jobs.page} 页</p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page <= 1 || loading} onClick={() => setPage((value) => Math.max(1, value - 1))}><BiChevronLeft />上一页</Button>
              <Button variant="outline" size="sm" disabled={!jobs.hasNext || loading} onClick={() => setPage((value) => value + 1)}>下一页<BiChevronRight /></Button>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
