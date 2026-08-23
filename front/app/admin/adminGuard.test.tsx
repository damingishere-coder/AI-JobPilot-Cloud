import { render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import AppShell from "@/app/components/AppShell"
import type { AuthUser } from "@/app/components/AuthProvider"

const navigation = vi.hoisted(() => ({
  pathname: "/",
  replace: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => ({ replace: navigation.replace }),
}))

const authMock = vi.hoisted(() => ({
  enabled: true,
  loading: false,
  user: null as AuthUser | null,
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({
    enabled: authMock.enabled,
    loading: authMock.loading,
    user: authMock.user,
    logout: vi.fn(),
  }),
}))

vi.mock("@/app/components/Sidebar", () => ({ default: () => <aside>侧边栏</aside> }))
vi.mock("@/app/components/ContentArea", () => ({
  default: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}))

const adminUser: AuthUser = { id: "u1", emailMasked: "a***@example.com", status: "ACTIVE", role: "ADMIN" }
const normalUser: AuthUser = { id: "u2", emailMasked: "u***@example.com", status: "ACTIVE", role: "USER" }

describe("后台路由角色守卫", () => {
  beforeEach(() => {
    navigation.pathname = "/"
    navigation.replace.mockReset()
    authMock.enabled = true
    authMock.loading = false
    authMock.user = null
  })

  it("普通用户访问 /admin 被重定向到首页且不渲染后台内容", async () => {
    authMock.user = normalUser
    navigation.pathname = "/admin"
    render(<AppShell><div>后台内容</div></AppShell>)

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"))
    expect(screen.queryByText("后台内容")).not.toBeInTheDocument()
    expect(screen.queryByText("侧边栏")).not.toBeInTheDocument()
  })

  it("普通用户访问 /admin/user?id={id} 同样被重定向", async () => {
    authMock.user = normalUser
    navigation.pathname = "/admin/user"
    render(<AppShell><div>后台内容</div></AppShell>)

    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/"))
    expect(screen.queryByText("后台内容")).not.toBeInTheDocument()
  })

  it("管理员访问 /admin 正常渲染且不触发跳转", async () => {
    authMock.user = adminUser
    navigation.pathname = "/admin"
    render(<AppShell><div>后台内容</div></AppShell>)

    expect(screen.getByText("后台内容")).toBeInTheDocument()
    expect(screen.getByText("侧边栏")).toBeInTheDocument()
    expect(navigation.replace).not.toHaveBeenCalled()
  })

  it("普通用户访问非后台路由不受影响", async () => {
    authMock.user = normalUser
    navigation.pathname = "/boss"
    render(<AppShell><div>工作台内容</div></AppShell>)

    expect(screen.getByText("工作台内容")).toBeInTheDocument()
    expect(navigation.replace).not.toHaveBeenCalled()
  })
})
