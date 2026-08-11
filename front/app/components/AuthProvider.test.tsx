import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import AppShell from "./AppShell"
import { AuthProvider, useAuth } from "./AuthProvider"

const navigation = vi.hoisted(() => ({
  pathname: "/boss",
  replace: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => ({ replace: navigation.replace }),
}))

vi.mock("./Sidebar", () => ({ default: () => <aside>侧边栏</aside> }))
vi.mock("./ContentArea", () => ({
  default: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}))

const authenticatedUser = {
  id: "11111111-1111-1111-1111-111111111111",
  emailMasked: "te***@example.com",
  status: "ACTIVE",
  role: "USER",
  profile: {
    displayName: null,
    city: null,
    timezone: "Asia/Shanghai",
    locale: "zh-CN",
  },
  quotaSummary: [],
  sessionExpiresAt: "2026-08-12T12:00:00Z",
  csrfToken: "csrf-from-me",
}

function apiResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function MeAndAction({ action }: { action: "expire" | "csrf" | "logout" }) {
  const auth = useAuth()
  const run = async () => {
    if (action === "logout") {
      await auth.logout()
      return
    }
    await auth.secureRequest("/api/protected", { method: "POST", body: "{}" })
  }
  return (
    <>
      <span>{auth.user?.emailMasked ?? "anonymous"}</span>
      <button onClick={() => void run().catch(() => undefined)}>执行</button>
    </>
  )
}

describe("Cloud 认证上下文与路由守卫", () => {
  beforeEach(() => {
    navigation.pathname = "/boss"
    navigation.replace.mockReset()
    window.history.replaceState({}, "", "/boss?tab=pending")
  })

  it("匿名访问受保护路由时保留站内 next 并跳转登录", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(apiResponse({
      success: false,
      error: { code: "AUTH_REQUIRED", message: "请先登录" },
      requestId: "request-anonymous",
    }, 401)))

    render(
      <AuthProvider>
        <AppShell><div>受保护内容</div></AppShell>
      </AuthProvider>,
    )

    await waitFor(() => {
      expect(navigation.replace).toHaveBeenCalledWith("/login?next=%2Fboss%3Ftab%3Dpending")
    })
    expect(screen.queryByText("受保护内容")).not.toBeInTheDocument()
  })

  it("Session 过期后的 401 会清理用户并跳转登录", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(apiResponse({ success: true, data: authenticatedUser, requestId: "request-me" }))
      .mockResolvedValueOnce(apiResponse({
        success: false,
        error: { code: "AUTH_REQUIRED", message: "请先登录" },
        requestId: "request-expired",
      }, 401))
    vi.stubGlobal("fetch", fetchMock)

    render(
      <AuthProvider>
        <AppShell><MeAndAction action="expire" /></AppShell>
      </AuthProvider>,
    )

    expect(await screen.findByText("te***@example.com")).toBeInTheDocument()
    navigation.replace.mockClear()
    fireEvent.click(screen.getByRole("button", { name: "执行" }))
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/login?next=%2Fboss%3Ftab%3Dpending"))
  })

  it("只对 CSRF_INVALID 刷新 Token 并重试一次原请求", async () => {
    const protectedTokens: string[] = []
    let protectedAttempts = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith("/api/me")) {
        return apiResponse({ success: true, data: authenticatedUser, requestId: "request-me" })
      }
      if (url.endsWith("/api/auth/csrf")) {
        return apiResponse({ success: true, data: { csrfToken: "csrf-refreshed" }, requestId: "request-csrf" })
      }
      if (url.endsWith("/api/protected")) {
        protectedAttempts++
        protectedTokens.push((init?.headers as Headers).get("X-CSRF-TOKEN") ?? "")
        if (protectedAttempts === 1) {
          return apiResponse({
            success: false,
            error: { code: "CSRF_INVALID", message: "安全校验已失效" },
            requestId: "request-invalid-csrf",
          }, 403)
        }
        return apiResponse({ success: true, data: { accepted: true }, requestId: "request-retry" })
      }
      throw new Error("意外请求：" + url)
    })
    vi.stubGlobal("fetch", fetchMock)

    render(
      <AuthProvider>
        <MeAndAction action="csrf" />
      </AuthProvider>,
    )

    expect(await screen.findByText("te***@example.com")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "执行" }))
    await waitFor(() => expect(protectedAttempts).toBe(2))
    expect(protectedTokens).toEqual(["csrf-from-me", "csrf-refreshed"])
  })

  it("退出成功后清理内存中的当前用户", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(apiResponse({ success: true, data: authenticatedUser, requestId: "request-me" }))
      .mockResolvedValueOnce(apiResponse({
        success: true,
        data: { loggedOut: true },
        requestId: "request-logout",
      }))
    vi.stubGlobal("fetch", fetchMock)

    render(
      <AuthProvider>
        <MeAndAction action="logout" />
      </AuthProvider>,
    )

    expect(await screen.findByText("te***@example.com")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "执行" }))
    expect(await screen.findByText("anonymous")).toBeInTheDocument()
    const logoutInit = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect((logoutInit.headers as Headers).get("X-CSRF-TOKEN")).toBe("csrf-from-me")
  })
})
