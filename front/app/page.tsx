"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { BiBriefcase, BiCheckCircle, BiFile, BiHomeAlt, BiLoaderAlt, BiRefresh, BiTargetLock } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

type Resume = { parseStatus: string; originalFilename: string }
type Preference = { version: number; targetTitles: string[] }
type Jobs = { total: number }

export default function CloudWorkbenchPage() {
  const { secureRequest } = useAuth()
  const [resume, setResume] = useState<Resume | null>(null)
  const [preference, setPreference] = useState<Preference | null>(null)
  const [jobCount, setJobCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [currentResume, currentPreference, jobs] = await Promise.all([
        secureRequest<Resume | null>("/api/resumes/current"),
        secureRequest<Preference | null>("/api/preferences"),
        secureRequest<Jobs>("/api/jobs?page=1&size=1"),
      ])
      setResume(currentResume)
      setPreference(currentPreference)
      setJobCount(jobs.total)
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "工作台加载失败")
    } finally {
      setLoading(false)
    }
  }, [secureRequest])

  useEffect(() => { void load() }, [load])

  const cards = [
    { title: "当前简历", value: resume ? (resume.parseStatus === "PARSED" ? "已就绪" : "处理中") : "未上传", detail: resume?.originalFilename ?? "上传简历并提取文本", href: "/resume", icon: <BiFile />, ready: resume?.parseStatus === "PARSED" },
    { title: "求职目标", value: preference ? `第 ${preference.version} 版` : "未配置", detail: preference?.targetTitles.join("、") || "设置职位、城市和薪资范围", href: "/preferences", icon: <BiTargetLock />, ready: Boolean(preference) },
    { title: "岗位池", value: `${jobCount} 条`, detail: jobCount ? "查看属于你的岗位" : "等待后续插件采集岗位", href: "/jobs", icon: <BiBriefcase />, ready: jobCount > 0 },
  ]

  return (
    <div className="space-y-5">
      <PageHeader icon={<BiHomeAlt size={28} />} title="Cloud 工作台" subtitle="简历、求职目标、岗位、投递任务、插件和额度统一管理" actions={<Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>} />
      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {loading ? <div className="flex items-center justify-center gap-2 py-24 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在加载工作台…</div> : (
        <div className="grid gap-4 xl:grid-cols-3">
          {cards.map((card) => (
            <Card key={card.title}>
              <CardHeader><div className="flex items-center justify-between"><div className="flex h-11 w-11 items-center justify-center rounded-lg bg-blue-50 text-xl text-blue-600">{card.icon}</div>{card.ready && <BiCheckCircle className="text-xl text-emerald-500" />}</div><CardTitle className="pt-3">{card.title}</CardTitle><CardDescription>{card.detail}</CardDescription></CardHeader>
              <CardContent><p className="mb-4 text-3xl font-bold text-slate-950">{card.value}</p><Button asChild variant={card.ready ? "outline" : "default"} className="w-full"><Link href={card.href}>{card.ready ? "查看详情" : "立即设置"}</Link></Button></CardContent>
            </Card>
          ))}
        </div>
      )}
      <Card><CardHeader><CardTitle>P9 已完成，P10 验收中</CardTitle><CardDescription>云端主链路已经具备，腾讯云真实部署与恢复演练尚未完成。</CardDescription></CardHeader><CardContent><p className="text-sm leading-7 text-slate-600">当前版本包含用户隔离、简历与求职目标、岗位池、AI 匹配、投递清单、插件任务、额度和基础后台。服务器不会保存招聘平台密码、Cookie 或浏览器登录会话；在 P10 人工验收全部通过前，不邀请真实用户。</p></CardContent></Card>
    </div>
  )
}
