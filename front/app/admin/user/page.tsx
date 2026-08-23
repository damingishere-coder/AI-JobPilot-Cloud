import { Suspense } from "react"
import AdminUserDetailClient from "./AdminUserDetailClient"

export default function AdminUserDetailRoute() {
  return (
    <Suspense fallback={<div className="py-24 text-center text-sm text-slate-500">正在加载用户详情…</div>}>
      <AdminUserDetailClient />
    </Suspense>
  )
}
