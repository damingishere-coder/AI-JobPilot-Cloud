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

  useEffect(() => {
    if (!enabled || loading || isPublic || user) return
    const current = `${window.location.pathname}${window.location.search}`
    router.replace(`/login?next=${encodeURIComponent(current)}`)
  }, [enabled, loading, isPublic, router, user])

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
  return (
    <div className="flex min-h-screen min-w-[1180px]">
      <Sidebar />
      <ContentArea>{children}</ContentArea>
    </div>
  )
}
