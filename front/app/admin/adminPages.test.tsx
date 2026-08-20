import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import AdminDashboardPage from "@/app/admin/page"
import AdminUserDetailPage from "@/app/admin/user/page"
import { AuthApiError } from "@/lib/authApi"
import type {
  AuditLogView,
  DashboardView,
  DeliveryFailureView,
  QuotaAdjustResult,
  ResourceQuotaView,
  UserAdminView,
  UserPage,
  UserQuotaRowView,
} from "@/lib/adminTypes"

const mocks = vi.hoisted(() => ({
  secureRequest: vi.fn(),
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ secureRequest: mocks.secureRequest }),
}))

vi.mock("@/app/components/PageHeader", () => ({
  default: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("id=11111111-1111-4111-8111-111111111111"),
  usePathname: () => "/admin",
}))

const userId = "11111111-1111-4111-8111-111111111111"

function quota(resourceCode: string, total: number, used: number): ResourceQuotaView {
  return { resourceCode, total, used, reserved: 2, remaining: total - used - 2 }
}

const dashboard: DashboardView = {
  totalUsers: 12, activeUsers: 10, jobs: 34, aiAnalyses: 56,
  deliveryTasks: 8, successCount: 6, failedCount: 2, activeDevices: 3, recentFailures: 1,
}

const userView: UserAdminView = {
  id: userId,
  emailMasked: "t***@example.com",
  role: "USER",
  status: "ACTIVE",
  createdAt: "2026-08-01T00:00:00Z",
  plan: "FREE",
  analysisQuota: quota("AI_ANALYSIS", 20, 5),
  deliveryQuota: quota("DELIVERY_CONFIRM", 10, 3),
  jobCount: 4,
  aiAnalysisCount: 5,
  deliveryTaskCount: 2,
  successCount: 1,
  failedCount: 1,
  activeDeviceCount: 1,
  totalCount: 1,
}

const userPage: UserPage = { total: 1, users: [userView] }

const auditLogs: AuditLogView[] = [
  {
    id: 1, userId, userEmailMasked: "a***@example.com", actorType: "ADMIN",
    action: "ADMIN_QUOTA_ADJUSTED", targetType: "USER", targetId: userId,
    result: "SUCCESS", createdAt: "2026-08-15T08:00:00Z",
  },
]

const failures: DeliveryFailureView[] = [
  {
    taskId: "33333333-3333-4333-8333-333333333333", userId, emailMasked: "f***@example.com",
    platform: "BOSS", status: "FAILED", lastErrorCode: "NETWORK_ERROR",
    errorMessage: "网络或页面加载异常", updatedAt: "2026-08-15T09:00:00Z",
  },
]

const quotaRows: UserQuotaRowView[] = [
  { quotaId: "q1", plan: "FREE", resourceCode: "AI_ANALYSIS", total: 20, used: 5, reserved: 2, remaining: 13, resetAt: "2026-09-01T00:00:00Z" },
  { quotaId: "q2", plan: "FREE", resourceCode: "DELIVERY_CONFIRM", total: 10, used: 3, reserved: 2, remaining: 5, resetAt: "2026-09-01T00:00:00Z" },
]

const adjustResult: QuotaAdjustResult = {
  plan: "PREMIUM_MONTHLY",
  analysisQuota: quota("AI_ANALYSIS", 100, 5),
  deliveryQuota: quota("DELIVERY_CONFIRM", 50, 3),
}

function routeRequests() {
  mocks.secureRequest.mockImplementation(async (path: string, init?: RequestInit) => {
    const url = String(path)
    if (url === "/api/admin/dashboard") return dashboard
    if (url === "/api/admin/users?page=0&size=20") return userPage
    if (url === "/api/admin/audit-logs?limit=20") return auditLogs
    if (url === "/api/admin/delivery-failures?limit=20") return failures
    if (url === `/api/admin/users/${userId}`) return userView
    if (url === `/api/admin/users/${userId}/quota` && init?.method === "PUT") return adjustResult
    if (url === `/api/admin/users/${userId}/quota`) return quotaRows
    throw new Error(`意外请求：${path}`)
  })
}

function putCalls() {
  return mocks.secureRequest.mock.calls.filter(
    ([path, init]) => String(path) === `/api/admin/users/${userId}/quota` && init?.method === "PUT",
  )
}

describe("基础后台页面", () => {
  let keyCounter = 0

  beforeEach(() => {
    mocks.secureRequest.mockReset()
    keyCounter = 0
    vi.stubGlobal("crypto", {
      randomUUID: () => `11111111-1111-4111-8111-${String(keyCounter++).padStart(12, "0")}`,
    })
  })

  it("后台总览并行发起四个接口并展示统计与脱敏邮箱", async () => {
    routeRequests()
    render(<AdminDashboardPage />)

    expect(await screen.findByText("后台管理")).toBeInTheDocument()
    await waitFor(() => {
      const paths = mocks.secureRequest.mock.calls.map(([path]) => String(path))
      expect(paths).toContain("/api/admin/dashboard")
      expect(paths).toContain("/api/admin/users?page=0&size=20")
      expect(paths).toContain("/api/admin/audit-logs?limit=20")
      expect(paths).toContain("/api/admin/delivery-failures?limit=20")
    })

    // 统计卡片
    expect(screen.getByText("总用户")).toBeInTheDocument()
    expect(screen.getByText("12")).toBeInTheDocument()
    expect(screen.getByText("活跃用户")).toBeInTheDocument()
    expect(screen.getByText("34")).toBeInTheDocument()
    expect(screen.getByText("AI 分析次数")).toBeInTheDocument()
    expect(screen.getByText("56")).toBeInTheDocument()
    expect(screen.getByText("近 7 天失败")).toBeInTheDocument()

    // 用户表：脱敏邮箱 + 行链接 + 不出现完整邮箱
    expect(screen.getByText("t***@example.com")).toBeInTheDocument()
    expect(screen.queryByText("admin-target@example.com")).not.toBeInTheDocument()
    expect(screen.getByRole("link", { name: "t***@example.com" })).toHaveAttribute("href", `/admin/user?id=${userId}`)
    expect(screen.getByText("免费版")).toBeInTheDocument()

    // 最近失败与最近审计窄字段表
    expect(screen.getByText("最近失败投递")).toBeInTheDocument()
    expect(screen.getByText("f***@example.com")).toBeInTheDocument()
    expect(screen.getByText("最近审计记录")).toBeInTheDocument()
    expect(screen.getByText("a***@example.com")).toBeInTheDocument()
    expect(screen.getByText("调整额度")).toBeInTheDocument()
  })

  it("后台总览接口失败时展示中文错误而非原始异常", async () => {
    mocks.secureRequest.mockRejectedValue(new AuthApiError(403, "FORBIDDEN", "没有权限执行该操作"))
    render(<AdminDashboardPage />)

    expect(await screen.findByRole("alert")).toHaveTextContent("没有权限执行该操作")
    expect(screen.getByText("后台数据加载失败，请稍后刷新重试。")).toBeInTheDocument()
    expect(screen.queryByText("用户列表")).not.toBeInTheDocument()
  })

  it("后台总览空数据时展示安全空状态", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url === "/api/admin/dashboard") return dashboard
      if (url === "/api/admin/users?page=0&size=20") return { total: 0, users: [] }
      if (url === "/api/admin/audit-logs?limit=20") return []
      if (url === "/api/admin/delivery-failures?limit=20") return []
      throw new Error(`意外请求：${path}`)
    })
    render(<AdminDashboardPage />)

    expect(await screen.findByText("暂无用户")).toBeInTheDocument()
    expect(screen.getByText("暂无失败记录")).toBeInTheDocument()
    expect(screen.getByText("暂无审计记录")).toBeInTheDocument()
  })

  it("用户详情调额 PUT 携带 Idempotency-Key 且不渲染敏感字段", async () => {
    routeRequests()
    render(<AdminUserDetailPage />)
    await screen.findByText("用户基础信息")

    // 只展示脱敏邮箱，不渲染完整邮箱或任何敏感字段
    expect(screen.getByText("t***@example.com")).toBeInTheDocument()
    expect(screen.queryByText("admin-target@example.com")).not.toBeInTheDocument()
    expect(screen.queryByText(/passwordHash|password/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/api ?key|token|cookie/i)).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText("AI 分析额度总量"), { target: { value: "100" } })
    fireEvent.change(screen.getByLabelText("投递额度总量"), { target: { value: "50" } })
    fireEvent.change(screen.getByLabelText("调整原因（必填）"), { target: { value: "运营调整" } })
    fireEvent.click(screen.getByRole("button", { name: "提交调整" }))

    await waitFor(() => expect(putCalls().length).toBe(1))
    const init = putCalls()[0][1] as RequestInit
    expect(JSON.parse(String(init.body))).toEqual({
      plan: "FREE", analysisQuotaTotal: 100, deliveryQuotaTotal: 50, reason: "运营调整",
    })
    const key = (init.headers as Record<string, string>)["Idempotency-Key"]
    expect(key).toBeTruthy()
    expect(key.length).toBeLessThanOrEqual(128)

    expect(await screen.findByText("额度调整成功，已写入审计记录")).toBeInTheDocument()
    // 成功后重新加载额度行
    await waitFor(() => {
      const quotaGets = mocks.secureRequest.mock.calls.filter(
        ([path, requestInit]) => String(path) === `/api/admin/users/${userId}/quota` && requestInit?.method !== "PUT",
      )
      expect(quotaGets.length).toBeGreaterThanOrEqual(2)
    })
  })

  it("套餐下拉可选择并提交新套餐", async () => {
    routeRequests()
    render(<AdminUserDetailPage />)
    await screen.findByText("用户基础信息")

    fireEvent.click(screen.getByLabelText("套餐"))
    fireEvent.click(await screen.findByText("月卡"))
    fireEvent.change(screen.getByLabelText("AI 分析额度总量"), { target: { value: "100" } })
    fireEvent.change(screen.getByLabelText("投递额度总量"), { target: { value: "50" } })
    fireEvent.change(screen.getByLabelText("调整原因（必填）"), { target: { value: "升级" } })
    fireEvent.click(screen.getByRole("button", { name: "提交调整" }))

    await waitFor(() => expect(putCalls().length).toBe(1))
    const init = putCalls()[0][1] as RequestInit
    expect(JSON.parse(String(init.body))).toEqual({
      plan: "MONTHLY", analysisQuotaTotal: 100, deliveryQuotaTotal: 50, reason: "升级",
    })
  })

  it("调额失败重试复用同一幂等键，修改表单后生成新键", async () => {
    let putAttempts = 0
    mocks.secureRequest.mockImplementation(async (path: string, init?: RequestInit) => {
      const url = String(path)
      if (url === `/api/admin/users/${userId}`) return userView
      if (url === `/api/admin/users/${userId}/quota` && init?.method === "PUT") {
        putAttempts += 1
        if (putAttempts === 1) {
          throw new AuthApiError(502, "BAD_GATEWAY", "后端服务暂不可用（HTTP 502），请稍后重试")
        }
        return adjustResult
      }
      if (url === `/api/admin/users/${userId}/quota`) return quotaRows
      throw new Error(`意外请求：${path}`)
    })
    render(<AdminUserDetailPage />)
    await screen.findByText("用户基础信息")

    fireEvent.change(screen.getByLabelText("AI 分析额度总量"), { target: { value: "100" } })
    fireEvent.change(screen.getByLabelText("投递额度总量"), { target: { value: "50" } })
    fireEvent.change(screen.getByLabelText("调整原因（必填）"), { target: { value: "运营调整" } })
    fireEvent.click(screen.getByRole("button", { name: "提交调整" }))
    await waitFor(() => expect(putCalls().length).toBe(1))
    expect(screen.getByRole("alert")).toHaveTextContent("后端服务暂不可用（HTTP 502），请稍后重试")

    // 同一次提交失败后重试：复用当前幂等键
    fireEvent.click(screen.getByRole("button", { name: "提交调整" }))
    await waitFor(() => expect(putCalls().length).toBe(2))
    const keyOf = (call: unknown[]) => (call[1] as RequestInit).headers as Record<string, string>
    expect(keyOf(putCalls()[0])["Idempotency-Key"]).toBe(keyOf(putCalls()[1])["Idempotency-Key"])

    // 修改表单后提交：生成新的幂等键
    fireEvent.change(screen.getByLabelText("AI 分析额度总量"), { target: { value: "200" } })
    fireEvent.change(screen.getByLabelText("调整原因（必填）"), { target: { value: "再次调整" } })
    fireEvent.click(screen.getByRole("button", { name: "提交调整" }))
    await waitFor(() => expect(putCalls().length).toBe(3))
    expect(keyOf(putCalls()[2])["Idempotency-Key"]).not.toBe(keyOf(putCalls()[0])["Idempotency-Key"])
  })
})
