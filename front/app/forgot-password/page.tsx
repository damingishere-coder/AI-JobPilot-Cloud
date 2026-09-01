"use client"

import Link from "next/link"
import { FormEvent, useState } from "react"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("")
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState("")

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError("")
    try {
      await cloudApiRequest("/api/auth/password-reset/request", {
        method: "POST",
        body: JSON.stringify({ email }),
      }, (await cloudApiRequest<{ csrfToken: string }>("/api/auth/csrf")).csrfToken)
      setSubmitted(true)
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "请求失败，请稍后再试")
    }
  }

  return <AuthPageFrame title="重置密码" description="重置链接 30 分钟内有效且只能使用一次" footer={<Link href="/login" className="font-semibold text-blue-600">返回登录</Link>}>
    {submitted ? <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm leading-7 text-blue-800">如果该邮箱已注册，我们会发送密码重置邮件。请检查收件箱和垃圾邮件。</div> :
      <form className="space-y-5" onSubmit={submit}>
        <div className="space-y-2"><Label htmlFor="email">邮箱</Label><Input id="email" type="email" required maxLength={254} value={email} onChange={(event) => setEmail(event.target.value)} /></div>
        {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
        <Button type="submit" className="w-full">发送重置邮件</Button>
      </form>}
  </AuthPageFrame>
}
