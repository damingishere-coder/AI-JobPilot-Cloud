"use client"

import Link from "next/link"
import { FormEvent, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { BiLoaderAlt, BiLockAlt, BiMailSend } from "react-icons/bi"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError, safeNextPath } from "@/lib/authApi"

export default function LoginPage() {
  const router = useRouter()
  const { enabled, loading, user, login } = useAuth()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [rememberMe, setRememberMe] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState("")

  useEffect(() => {
    if (!loading && user) {
      const next = safeNextPath(new URLSearchParams(window.location.search).get("next"))
      router.replace(next)
    }
  }, [loading, router, user])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError("")
    setSubmitting(true)
    try {
      await login(email, password, rememberMe)
      const next = safeNextPath(new URLSearchParams(window.location.search).get("next"))
      router.replace(next)
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "登录失败，请稍后再试")
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthPageFrame
      title="登录 Cloud"
      description="使用邮箱进入你的独立求职工作台"
      footer={<>还没有账号？ <Link href="/register" className="font-semibold text-blue-600 hover:text-blue-700">立即注册</Link></>}
    >
      {!enabled && (
        <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          当前是旧本地模式，Cloud 登录守卫未启用。
        </div>
      )}
      <form className="space-y-5" onSubmit={submit}>
        <div className="space-y-2">
          <Label htmlFor="email">邮箱</Label>
          <div className="relative">
            <BiMailSend className="pointer-events-none absolute left-3 top-3 text-lg text-slate-400" />
            <Input id="email" type="email" autoComplete="email" maxLength={254} required value={email} onChange={(event) => setEmail(event.target.value)} className="pl-10" placeholder="name@example.com" />
          </div>
        </div>
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <Label htmlFor="password">密码</Label>
            <Link href="/forgot-password" className="text-xs font-semibold text-blue-600 hover:underline">忘记密码？</Link>
          </div>
          <div className="relative">
            <BiLockAlt className="pointer-events-none absolute left-3 top-3 text-lg text-slate-400" />
            <Input id="password" type="password" autoComplete="current-password" minLength={12} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} className="pl-10" placeholder="请输入密码" />
          </div>
        </div>
        <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
          <input type="checkbox" checked={rememberMe} onChange={(event) => setRememberMe(event.target.checked)} className="h-4 w-4 rounded border-slate-300 text-blue-600" />
          记住我 30 天
        </label>
        <p className="text-right text-xs"><Link href="/resend-verification" className="font-semibold text-blue-600 hover:underline">未收到验证邮件？</Link></p>
        {error && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</p>}
        <Button type="submit" className="w-full" size="lg" disabled={submitting || loading || !enabled}>
          {submitting && <BiLoaderAlt className="animate-spin" />}
          {submitting ? "正在登录…" : "登录"}
        </Button>
      </form>
    </AuthPageFrame>
  )
}
