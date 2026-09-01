"use client"

import Link from "next/link"
import { useEffect, useState } from "react"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export default function VerifyEmailPage() {
  const [message, setMessage] = useState("正在验证邮箱…")
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    const token = new URLSearchParams(window.location.search).get("token") || ""
    void (async () => {
      try {
        if (!token) throw new AuthApiError(400, "TOKEN_INVALID", "验证链接无效")
        const csrf = await cloudApiRequest<{ csrfToken: string }>("/api/auth/csrf")
        await cloudApiRequest("/api/auth/email-verification/confirm", { method: "POST", body: JSON.stringify({ token }) }, csrf.csrfToken)
        setMessage("邮箱验证成功，现在可以登录。")
      } catch (caught) {
        setFailed(true)
        setMessage(caught instanceof AuthApiError ? caught.message : "验证失败，请重新申请验证邮件")
      }
    })()
  }, [])
  return <AuthPageFrame title="邮箱验证" description="验证链接 24 小时内有效" footer={<Link href="/login" className="font-semibold text-blue-600">返回登录</Link>}>
    <div role={failed ? "alert" : "status"} className={`rounded-xl border p-4 text-sm ${failed ? "border-rose-200 bg-rose-50 text-rose-800" : "border-blue-200 bg-blue-50 text-blue-800"}`}>{message}</div>
  </AuthPageFrame>
}
