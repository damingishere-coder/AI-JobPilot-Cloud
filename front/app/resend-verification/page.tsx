"use client"

import Link from "next/link"
import { FormEvent, useState } from "react"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export default function ResendVerificationPage() {
  const [email, setEmail] = useState("")
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState("")
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError("")
    try {
      const csrf = await cloudApiRequest<{ csrfToken: string }>("/api/auth/csrf")
      await cloudApiRequest("/api/auth/email-verification/request", { method: "POST", body: JSON.stringify({ email }) }, csrf.csrfToken)
      setSubmitted(true)
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "请求失败，请稍后再试")
    }
  }
  return <AuthPageFrame title="重新发送验证邮件" description="验证链接 24 小时内有效" footer={<Link href="/login" className="font-semibold text-blue-600">返回登录</Link>}>
    {submitted ? <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm leading-7 text-blue-800">如果该邮箱存在待验证账号，我们会发送新的验证邮件。</div> :
      <form className="space-y-5" onSubmit={submit}>
        <div className="space-y-2"><Label htmlFor="email">邮箱</Label><Input id="email" type="email" required maxLength={254} value={email} onChange={(event) => setEmail(event.target.value)} /></div>
        {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
        <Button type="submit" className="w-full">重新发送</Button>
      </form>}
  </AuthPageFrame>
}
