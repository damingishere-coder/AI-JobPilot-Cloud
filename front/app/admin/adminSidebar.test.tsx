import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import Sidebar from "@/app/components/Sidebar"
import type { AuthUser } from "@/app/components/AuthProvider"

const authMock = vi.hoisted(() => ({
  user: null as AuthUser | null,
}))

const navigation = vi.hoisted(() => ({ pathname: "/" }))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ enabled: true, user: authMock.user, logout: vi.fn() }),
}))

vi.mock("next/navigation", () => ({ usePathname: () => navigation.pathname }))
vi.mock("next-themes", () => ({ useTheme: () => ({ theme: "light", setTheme: vi.fn() }) }))

vi.mock("next/image", async () => {
  const React = await import("react")
  return { default: (props: Record<string, unknown>) => React.createElement("img", props) }
})

vi.mock("framer-motion", async () => {
  const React = await import("react")
  const passthrough = (props: Record<string, unknown>) => React.createElement("div", props)
  return { motion: new Proxy({}, { get: () => passthrough }) }
})

const normalUser: AuthUser = { id: "u1", emailMasked: "u***@example.com", status: "ACTIVE", role: "USER" }
const adminUser: AuthUser = { id: "u2", emailMasked: "a***@example.com", status: "ACTIVE", role: "ADMIN" }

describe("侧边栏后台入口", () => {
  beforeEach(() => {
    authMock.user = null
    navigation.pathname = "/"
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ status: "UP" }),
    }))
  })

  it("普通用户侧栏不出现后台入口", () => {
    authMock.user = normalUser
    render(<Sidebar />)

    expect(screen.queryByText("后台管理")).not.toBeInTheDocument()
    expect(screen.queryByText("系统管理")).not.toBeInTheDocument()
  })

  it("未登录用户侧栏不出现后台入口", () => {
    render(<Sidebar />)

    expect(screen.queryByText("后台管理")).not.toBeInTheDocument()
    expect(screen.queryByText("系统管理")).not.toBeInTheDocument()
  })

  it("ADMIN 侧栏出现后台管理入口并指向 /admin", () => {
    authMock.user = adminUser
    render(<Sidebar />)

    expect(screen.getByText("系统管理")).toBeInTheDocument()
    const link = screen.getByRole("link", { name: "后台管理" })
    expect(link).toHaveAttribute("href", "/admin")
  })
})
