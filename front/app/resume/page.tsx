"use client"

import { FormEvent, useCallback, useEffect, useRef, useState } from "react"
import { BiCheckCircle, BiErrorCircle, BiFile, BiLoaderAlt, BiRefresh, BiTrash, BiUpload } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { AuthApiError } from "@/lib/authApi"

type Resume = {
  id: string
  originalFilename: string
  contentType: string
  fileSize: number
  parseStatus: "UPLOADED" | "PARSING" | "PARSED" | "FAILED"
  parseMessage: string | null
  current: boolean
  version: number
  createdAt: string
  updatedAt: string
  parsedAt: string | null
  extractedText: string | null
}

type PageResult<T> = {
  items: T[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

const statusLabels: Record<Resume["parseStatus"], string> = {
  UPLOADED: "等待解析",
  PARSING: "正在提取文本",
  PARSED: "解析成功",
  FAILED: "解析失败",
}

function displayError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function formatBytes(value: number) {
  return value >= 1024 * 1024
    ? `${(value / 1024 / 1024).toFixed(2)} MiB`
    : `${Math.max(1, Math.round(value / 1024))} KiB`
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"
}

export default function ResumePage() {
  const { secureRequest } = useAuth()
  const [current, setCurrent] = useState<Resume | null>(null)
  const [history, setHistory] = useState<Resume[]>([])
  const [file, setFile] = useState<File | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [error, setError] = useState("")
  const inputRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true)
    try {
      const [active, page] = await Promise.all([
        secureRequest<Resume | null>("/api/resumes/current?includeExtractedText=true"),
        secureRequest<PageResult<Resume>>("/api/resumes?page=1&size=20"),
      ])
      setCurrent(active)
      setHistory(page.items)
      setError("")
    } catch (requestError) {
      setError(displayError(requestError, "简历加载失败"))
    } finally {
      if (!quiet) setLoading(false)
    }
  }, [secureRequest])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!current || !["UPLOADED", "PARSING"].includes(current.parseStatus)) return
    const timer = window.setInterval(() => void load(true), 2000)
    return () => window.clearInterval(timer)
  }, [current, load])

  const upload = async (event: FormEvent) => {
    event.preventDefault()
    if (!file) {
      setError("请先选择 PDF、DOCX 或 TXT 简历")
      return
    }
    setUploading(true)
    setError("")
    try {
      const body = new FormData()
      body.append("file", file)
      await secureRequest<{ resume: Resume; deduplicated: boolean }>(
        "/api/resumes/upload?setCurrent=true",
        {
          method: "POST",
          headers: { "Idempotency-Key": crypto.randomUUID() },
          body,
        },
      )
      setFile(null)
      if (inputRef.current) inputRef.current.value = ""
      await load(true)
    } catch (requestError) {
      setError(displayError(requestError, "简历上传失败"))
    } finally {
      setUploading(false)
    }
  }

  const remove = async (resume: Resume) => {
    if (!window.confirm(`确认删除“${resume.originalFilename}”？删除后文件和提取文本将不可恢复。`)) return
    setDeletingId(resume.id)
    setError("")
    try {
      await secureRequest(`/api/resumes/${resume.id}`, {
        method: "DELETE",
        headers: {
          "If-Match": String(resume.version),
          "Idempotency-Key": crypto.randomUUID(),
        },
      })
      await load(true)
    } catch (requestError) {
      if (requestError instanceof AuthApiError && requestError.code === "RESOURCE_VERSION_CONFLICT") {
        await load(true)
      }
      setError(displayError(requestError, "简历删除失败"))
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiFile size={28} />}
        title="我的简历"
        subtitle="文件经过安全扫描和加密保存，提取文本仅供本人查看"
        iconClass="text-violet-600"
        accentBgClass="bg-violet-50 dark:bg-violet-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && (
        <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
          {error}
        </div>
      )}

      <div className="grid gap-4 xl:grid-cols-[420px_1fr]">
        <Card>
          <CardHeader>
            <CardTitle>上传新简历</CardTitle>
            <CardDescription>支持 PDF、DOCX、TXT，单个文件不超过 10 MiB。新文件会成为当前简历。</CardDescription>
          </CardHeader>
          <CardContent>
            <form className="space-y-4" onSubmit={upload}>
              <label htmlFor="resume-file" className="block text-sm font-semibold text-slate-700">选择简历文件</label>
              <Input
                id="resume-file"
                ref={inputRef}
                type="file"
                accept=".pdf,.docx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                disabled={uploading}
              />
              {file && <p className="text-xs text-slate-500">已选择：{file.name} · {formatBytes(file.size)}</p>}
              <Button type="submit" className="w-full" disabled={!file || uploading}>
                {uploading ? <BiLoaderAlt className="animate-spin" /> : <BiUpload />}
                {uploading ? "正在安全检查并上传…" : "上传并设为当前简历"}
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>当前简历</CardTitle>
            <CardDescription>{current ? `最近更新 ${formatTime(current.updatedAt)}` : "还没有当前简历"}</CardDescription>
          </CardHeader>
          <CardContent>
            {loading ? (
              <div className="flex items-center gap-2 py-10 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在读取简历…</div>
            ) : current ? (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-slate-50 p-4">
                  <div>
                    <p className="font-semibold text-slate-900">{current.originalFilename}</p>
                    <p className="mt-1 text-xs text-slate-500">{formatBytes(current.fileSize)} · 版本 {current.version}</p>
                  </div>
                  <span className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold ${current.parseStatus === "PARSED" ? "bg-emerald-100 text-emerald-700" : current.parseStatus === "FAILED" ? "bg-rose-100 text-rose-700" : "bg-blue-100 text-blue-700"}`}>
                    {current.parseStatus === "PARSED" ? <BiCheckCircle /> : current.parseStatus === "FAILED" ? <BiErrorCircle /> : <BiLoaderAlt className="animate-spin" />}
                    {statusLabels[current.parseStatus]}
                  </span>
                </div>
                {current.parseMessage && <p className="text-sm text-slate-600">{current.parseMessage}</p>}
                {current.extractedText && (
                  <div>
                    <label htmlFor="resume-text" className="mb-2 block text-sm font-semibold text-slate-700">提取文本（只读）</label>
                    <Textarea id="resume-text" readOnly value={current.extractedText} className="min-h-72 resize-y font-mono text-xs leading-6" />
                  </div>
                )}
              </div>
            ) : (
              <div className="rounded-lg border border-dashed border-slate-300 px-5 py-12 text-center text-sm text-slate-500">上传简历后，解析状态和只读文本会显示在这里。</div>
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>上传记录</CardTitle>
          <CardDescription>旧版本仍加密保留，你可以逐份删除。</CardDescription>
        </CardHeader>
        <CardContent>
          {history.length === 0 ? (
            <p className="py-8 text-center text-sm text-slate-500">暂无上传记录</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 text-xs text-slate-500">
                  <tr><th className="px-3 py-3">文件</th><th className="px-3 py-3">状态</th><th className="px-3 py-3">上传时间</th><th className="px-3 py-3 text-right">操作</th></tr>
                </thead>
                <tbody>
                  {history.map((resume) => (
                    <tr key={resume.id} className="border-b border-slate-100 last:border-0">
                      <td className="px-3 py-3"><span className="font-medium text-slate-800">{resume.originalFilename}</span>{resume.current && <span className="ml-2 rounded bg-blue-50 px-2 py-0.5 text-[10px] text-blue-600">当前</span>}</td>
                      <td className="px-3 py-3 text-slate-600">{statusLabels[resume.parseStatus]}</td>
                      <td className="px-3 py-3 text-slate-500">{formatTime(resume.createdAt)}</td>
                      <td className="px-3 py-3 text-right"><Button variant="ghost" size="sm" onClick={() => void remove(resume)} disabled={deletingId === resume.id}><BiTrash />删除</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
