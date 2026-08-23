"use client"

import Link from "next/link"
import { FormEvent, useEffect, useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { BiLoaderAlt, BiLockAlt, BiMailSend } from "react-icons/bi"
import AuthPageFrame from "@/app/components/AuthPageFrame"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthApiError } from "@/lib/authApi"

export default function RegisterPage() {
  const router = useRouter()
  const { enabled, loading, user, register } = useAuth()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [acceptTerms, setAcceptTerms] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState("")

  useEffect(() => {
    if (!loading && user) router.replace("/")
  }, [loading, router, user])

  const strength = useMemo(() => {
    if (!password) return { label: "至少 12 个字符", color: "bg-slate-200", width: "w-0" }
    let score = password.length >= 12 ? 1 : 0
    if (password.length >= 16) score++
    if (/[^\p{L}\p{N}]/u.test(password)) score++
    if (/\p{L}/u.test(password) && /\p{N}/u.test(password)) score++
    if (score <= 1) return { label: "强度较弱", color: "bg-rose-500", width: "w-1/3" }
    if (score <= 3) return { label: "强度良好", color: "bg-amber-500", width: "w-2/3" }
    return { label: "强度较强", color: "bg-emerald-500", width: "w-full" }
  }, [password])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError("")
    if (password !== confirmPassword) {
      setError("两次输入的密码不一致")
      return
    }
    setSubmitting(true)
    try {
      await register(email, password, acceptTerms)
      router.replace("/")
    } catch (caught) {
      setError(caught instanceof AuthApiError ? caught.message : "注册失败，请稍后再试")
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthPageFrame
      title="创建账号"
      description="注册后立即进入开发试用版工作台"
      footer={<>已经有账号？ <Link href="/login" className="font-semibold text-blue-600 hover:text-blue-700">返回登录</Link></>}
    >
      <form className="space-y-5" onSubmit={submit}>
        <div className="space-y-2">
          <Label htmlFor="email">邮箱</Label>
          <div className="relative">
            <BiMailSend className="pointer-events-none absolute left-3 top-3 text-lg text-slate-400" />
            <Input id="email" type="email" autoComplete="email" maxLength={254} required value={email} onChange={(event) => setEmail(event.target.value)} className="pl-10" placeholder="name@example.com" />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="password">密码</Label>
          <div className="relative">
            <BiLockAlt className="pointer-events-none absolute left-3 top-3 text-lg text-slate-400" />
            <Input id="password" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} className="pl-10" placeholder="12–128 个字符" />
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-slate-100"><div className={`h-full rounded-full transition-all ${strength.color} ${strength.width}`} /></div>
          <p className="text-xs text-slate-500">{strength.label}；请勿使用邮箱或常见密码</p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="confirmPassword">确认密码</Label>
          <Input id="confirmPassword" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} placeholder="再次输入密码" />
        </div>
        <label className="flex cursor-pointer items-start gap-2 text-sm leading-6 text-slate-600 dark:text-slate-300">
          <input type="checkbox" required checked={acceptTerms} onChange={(event) => setAcceptTerms(event.target.checked)} className="mt-1 h-4 w-4 rounded border-slate-300 text-blue-600" />
          <span>我已阅读并同意 <Link href="/terms" className="text-blue-600 hover:underline">试用版服务条款</Link> 和 <Link href="/privacy" className="text-blue-600 hover:underline">隐私说明</Link></span>
        </label>
        {error && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</p>}
        <Button type="submit" className="w-full" size="lg" disabled={submitting || loading || !enabled}>
          {submitting && <BiLoaderAlt className="animate-spin" />}
          {submitting ? "正在创建账号…" : "注册并登录"}
        </Button>
      </form>
    </AuthPageFrame>
  )
}
