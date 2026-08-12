"use client"

import Link from "next/link"
import { FormEvent, useCallback, useEffect, useState } from "react"
import { BiBriefcase, BiChevronLeft, BiChevronRight, BiLoaderAlt, BiRefresh, BiSearch } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"

type Salary = { minK: number | null; maxK: number | null; months: number | null; text: string | null }
type Job = {
  id: string
  platform: string
  title: string
  companyName: string
  salary: Salary
  location: string | null
  status: "ACTIVE" | "EXPIRED" | "REMOVED"
  latestMatchSummary: null
  deliveryTaskStatus: null
  lastSeenAt: string
}
type PageResult<T> = { items: T[]; page: number; size: number; total: number; hasNext: boolean }

function salaryText(salary: Salary) {
  if (salary.text) return salary.text
  if (salary.minK !== null && salary.maxK !== null) return `${salary.minK}-${salary.maxK}K${salary.months ? `·${salary.months}薪` : ""}`
  return "薪资面议"
}

const platformLabels: Record<string, string> = { BOSS: "Boss直聘", ZHILIAN: "智联招聘", LIEPIN: "猎聘", JOB51: "51job" }
const statusLabels: Record<string, string> = { ACTIVE: "有效", EXPIRED: "已过期", REMOVED: "已下架" }

export default function JobsPage() {
  const { secureRequest } = useAuth()
  const [jobs, setJobs] = useState<PageResult<Job>>({ items: [], page: 1, size: 20, total: 0, hasNext: false })
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState("")
  const [submittedKeyword, setSubmittedKeyword] = useState("")
  const [platform, setPlatform] = useState("")
  const [status, setStatus] = useState("ACTIVE")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    const params = new URLSearchParams({ page: String(page), size: "20", sort: "lastSeenAt,desc" })
    if (submittedKeyword) params.set("keyword", submittedKeyword)
    if (platform) params.set("platform", platform)
    if (status) params.set("status", status)
    try {
      const result = await secureRequest<PageResult<Job>>(`/api/jobs?${params.toString()}`)
      setJobs(result)
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "岗位池加载失败")
    } finally {
      setLoading(false)
    }
  }, [page, platform, secureRequest, status, submittedKeyword])

  useEffect(() => {
    void load()
  }, [load])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setPage(1)
    setSubmittedKeyword(keyword.trim())
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiBriefcase size={28} />}
        title="岗位池"
        subtitle="这里展示属于当前账号的岗位；插件采集入口将在后续轮次开放"
        iconClass="text-blue-600"
        accentBgClass="bg-blue-50 dark:bg-blue-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      <Card>
        <CardContent className="pt-5">
          <form className="grid gap-3 md:grid-cols-[1fr_180px_160px_auto]" onSubmit={search}>
            <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索职位、公司或城市" maxLength={100} />
            <select aria-label="招聘平台" value={platform} onChange={(event) => { setPlatform(event.target.value); setPage(1) }} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部平台</option><option value="BOSS">Boss直聘</option><option value="ZHILIAN">智联招聘</option><option value="LIEPIN">猎聘</option><option value="JOB51">51job</option>
            </select>
            <select aria-label="岗位状态" value={status} onChange={(event) => { setStatus(event.target.value); setPage(1) }} className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm">
              <option value="">全部状态</option><option value="ACTIVE">有效</option><option value="EXPIRED">已过期</option><option value="REMOVED">已下架</option>
            </select>
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
              <p className="mt-1 text-sm text-slate-500">第四轮先完成安全存储和浏览；绑定浏览器插件后，采集的岗位会自动出现在这里。</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs text-slate-500"><tr><th className="px-3 py-3">岗位</th><th className="px-3 py-3">薪资</th><th className="px-3 py-3">地点</th><th className="px-3 py-3">平台</th><th className="px-3 py-3">状态</th><th className="px-3 py-3">最近采集</th></tr></thead>
                <tbody>
                  {jobs.items.map((job) => (
                    <tr key={job.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50/70">
                      <td className="px-3 py-4"><Link href={`/jobs/detail?id=${encodeURIComponent(job.id)}`} className="font-semibold text-blue-600 hover:underline">{job.title}</Link><p className="mt-1 text-xs text-slate-500">{job.companyName}</p></td>
                      <td className="px-3 py-4 text-slate-700">{salaryText(job.salary)}</td>
                      <td className="px-3 py-4 text-slate-600">{job.location || "—"}</td>
                      <td className="px-3 py-4 text-slate-600">{platformLabels[job.platform] ?? job.platform}</td>
                      <td className="px-3 py-4"><span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-600">{statusLabels[job.status] ?? job.status}</span></td>
                      <td className="px-3 py-4 text-xs text-slate-500">{new Date(job.lastSeenAt).toLocaleString("zh-CN", { hour12: false })}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-4">
            <p className="text-xs text-slate-500">第 {jobs.page} 页</p>
            <div className="flex gap-2"><Button variant="outline" size="sm" disabled={page <= 1 || loading} onClick={() => setPage((value) => Math.max(1, value - 1))}><BiChevronLeft />上一页</Button><Button variant="outline" size="sm" disabled={!jobs.hasNext || loading} onClick={() => setPage((value) => value + 1)}>下一页<BiChevronRight /></Button></div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
