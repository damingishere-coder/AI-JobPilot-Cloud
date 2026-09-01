"use client"

import { FormEvent, useState } from "react"
import { useRouter } from "next/navigation"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError } from "@/lib/authApi"

export default function AccountSettingsPage() {
  const router = useRouter()
  const { user, deleteAccount } = useAuth()
  const [password, setPassword] = useState("")
  const [confirmation, setConfirmation] = useState("")
  const [error, setError] = useState("")
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (confirmation !== "永久删除") return setError("请输入完整的“永久删除”")
    setSubmitting(true)
    setError("")
    try {
      await deleteAccount(password, confirmation)
      router.replace("/login?deleted=accepted")
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "删除请求失败，请稍后再试")
    } finally {
      setSubmitting(false)
    }
  }

  return <div className="mx-auto max-w-3xl space-y-6 p-8">
    <div><h1 className="text-2xl font-bold text-slate-950">账号与隐私</h1><p className="mt-2 text-sm text-slate-500">当前账号：{user?.emailMasked}</p></div>
    <section className="rounded-2xl border border-rose-200 bg-white p-6">
      <h2 className="text-lg font-bold text-rose-700">永久删除账号</h2>
      <p className="mt-3 text-sm leading-7 text-slate-600">此操作不可撤销。提交后立即退出并禁用账号，系统会在 24 小时内清理简历、附件、偏好、匹配和投递数据；只保留不含个人资料的最小删除凭证。</p>
      <form className="mt-5 space-y-4" onSubmit={submit}>
        <div className="space-y-2"><Label htmlFor="password">当前密码</Label><Input id="password" type="password" required minLength={12} maxLength={128} value={password} onChange={(event) => setPassword(event.target.value)} /></div>
        <div className="space-y-2"><Label htmlFor="confirmation">输入“永久删除”确认</Label><Input id="confirmation" required value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></div>
        {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
        <Button type="submit" variant="destructive" disabled={submitting}>{submitting ? "正在提交…" : "永久删除账号"}</Button>
      </form>
    </section>
  </div>
}
