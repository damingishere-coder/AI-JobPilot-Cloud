"use client"

import { useEffect } from "react"
import { usePathname, useRouter } from "next/navigation"
import Sidebar from "./Sidebar"
import ContentArea from "./ContentArea"
import { useAuth } from "./AuthProvider"

const publicRoutes = new Set(["/login", "/register", "/terms", "/privacy"])

export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const { enabled, loading, user } = useAuth()
  const isPublic = publicRoutes.has(pathname)
  const isAdminRoute = pathname === "/admin" || pathname.startsWith("/admin/")

  useEffect(() => {
    if (!enabled || loading || isPublic || user) return
    const current = `${window.location.pathname}${window.location.search}`
    router.replace(`/login?next=${encodeURIComponent(current)}`)
  }, [enabled, loading, isPublic, router, user])

  // 后台路由的客户端角色守卫：仅 ADMIN 可进入；检查期间不闪现后台内容。
  // 后端 /api/admin/** 仍是最终权限边界，这里只是避免普通用户看到入口与页面。
  useEffect(() => {
    if (!enabled || loading || isPublic || !isAdminRoute || !user) return
    if (user.role !== "ADMIN") {
      router.replace("/")
    }
  }, [enabled, isAdminRoute, loading, isPublic, router, user])

  if (isPublic) {
    return <>{children}</>
  }
  if (enabled && (loading || !user)) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-50 text-slate-600">
        <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-sm">
          <span className="h-5 w-5 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          正在检查登录状态…
        </div>
      </main>
    )
  }
  // 非 ADMIN 已登录用户访问后台路由：在跳转期间保持加载态，避免闪现后台内容。
  if (isAdminRoute && user?.role !== "ADMIN") {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-50 text-slate-600">
        <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-sm">
          <span className="h-5 w-5 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          正在检查登录状态…
        </div>
      </main>
    )
  }
  return (
    <div className="flex min-h-screen min-w-[1180px]">
      <Sidebar />
      <ContentArea>{children}</ContentArea>
    </div>
  )
}
