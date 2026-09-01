"use client"

import Link from "next/link"
import { FormEvent, useState } from "react"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export default function ResetPasswordPage() {
  const [token] = useState(() => typeof window === "undefined" ? "" : new URLSearchParams(window.location.search).get("token") || "")
  const [password, setPassword] = useState("")
  const [confirmed, setConfirmed] = useState("")
  const [done, setDone] = useState(false)
  const [error, setError] = useState("")
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!token) return setError("重置链接无效或缺少令牌")
    if (password !== confirmed) return setError("两次输入的密码不一致")
    try {
      const csrf = await cloudApiRequest<{ csrfToken: string }>("/api/auth/csrf")
      await cloudApiRequest("/api/auth/password-reset/confirm", { method: "POST", body: JSON.stringify({ token, newPassword: password }) }, csrf.csrfToken)
      setDone(true)
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "重置失败，请重新申请链接")
    }
  }

  return <AuthPageFrame title="设置新密码" description="使用新的强密码替换旧密码" footer={<Link href="/login" className="font-semibold text-blue-600">返回登录</Link>}>
    {done ? <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800">密码已更新，所有旧会话已撤销。现在可以返回登录。</div> :
      <form className="space-y-5" onSubmit={submit}>
        <div className="space-y-2"><Label htmlFor="password">新密码</Label><Input id="password" type="password" minLength={12} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} /></div>
        <div className="space-y-2"><Label htmlFor="confirmPassword">确认新密码</Label><Input id="confirmPassword" type="password" minLength={12} maxLength={128} required value={confirmed} onChange={(event) => setConfirmed(event.target.value)} /></div>
        {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
        <Button type="submit" className="w-full">确认重置密码</Button>
      </form>}
  </AuthPageFrame>
}
