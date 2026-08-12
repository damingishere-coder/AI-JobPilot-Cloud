"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { BiArrowBack, BiBriefcase, BiLinkExternal, BiLoaderAlt, BiRefresh } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

type Job = {
  id: string; platform: string; externalJobId: string | null; title: string; companyName: string
  salary: { minK: number | null; maxK: number | null; months: number | null; text: string | null }
  location: string | null; experience: string | null; degree: string | null; description: string | null
  jobUrl: string; companyInfo: Record<string, unknown>; skills: string[]; welfare: string[]
  status: string; capturedAt: string; lastSeenAt: string; latestMatch: null; deliveryTask: null
}

export default function JobDetailPage() {
  const { secureRequest } = useAuth()
  const [jobId, setJobId] = useState<string | null | undefined>(undefined)
  const [job, setJob] = useState<Job | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    if (!jobId) return
    setLoading(true)
    try {
      setJob(await secureRequest<Job>(`/api/jobs/${jobId}`))
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

  return (
    <div className="space-y-5">
      <PageHeader icon={<BiBriefcase size={28} />} title={job?.title ?? "岗位详情"} subtitle={job ? `${job.companyName} · ${job.platform}` : "查看岗位采集信息"} actions={<><Button asChild variant="outline" size="sm"><Link href="/jobs"><BiArrowBack />返回岗位池</Link></Button><Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button></>} />
      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {loading ? <div className="flex items-center justify-center gap-2 py-24 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载岗位详情…</div> : job && (
        <div className="grid gap-4 xl:grid-cols-[1fr_320px]">
          <Card><CardHeader><CardTitle>职位描述</CardTitle><CardDescription>采集时间 {new Date(job.capturedAt).toLocaleString("zh-CN", { hour12: false })}</CardDescription></CardHeader><CardContent><div className="whitespace-pre-wrap text-sm leading-7 text-slate-700">{job.description || "暂无职位描述"}</div></CardContent></Card>
          <div className="space-y-4">
            <Card><CardHeader><CardTitle>基本信息</CardTitle></CardHeader><CardContent className="space-y-3 text-sm"><p><span className="text-slate-400">公司：</span>{job.companyName}</p><p><span className="text-slate-400">薪资：</span>{job.salary.text || "面议"}</p><p><span className="text-slate-400">地点：</span>{job.location || "—"}</p><p><span className="text-slate-400">经验：</span>{job.experience || "—"}</p><p><span className="text-slate-400">学历：</span>{job.degree || "—"}</p><Button asChild className="mt-2 w-full"><a href={job.jobUrl} target="_blank" rel="noreferrer"><BiLinkExternal />打开原岗位</a></Button></CardContent></Card>
            <Card><CardHeader><CardTitle>技能与福利</CardTitle></CardHeader><CardContent><div className="flex flex-wrap gap-2">{[...job.skills, ...job.welfare].length === 0 ? <span className="text-sm text-slate-500">暂无结构化标签</span> : [...job.skills, ...job.welfare].map((item) => <span key={item} className="rounded-full bg-blue-50 px-3 py-1 text-xs text-blue-700">{item}</span>)}</div></CardContent></Card>
          </div>
        </div>
      )}
    </div>
  )
}
