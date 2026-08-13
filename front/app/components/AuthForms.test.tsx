import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import LoginPage from "@/app/login/page"
import RegisterPage from "@/app/register/page"
import { AuthApiError } from "@/lib/authApi"

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  replace: vi.fn(),
}))

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({
    enabled: true,
    loading: false,
    user: null,
    login: mocks.login,
    register: mocks.register,
  }),
}))

describe("登录和注册表单", () => {
  beforeEach(() => {
    mocks.login.mockReset()
    mocks.register.mockReset()
    mocks.replace.mockReset()
    window.history.replaceState({}, "", "/")
  })

  it("注册时阻止两次密码不一致", async () => {
    render(<RegisterPage />)
    fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "person@example.com" } })
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "StrongPassword!2026" } })
    fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "AnotherPassword!2026" } })
    fireEvent.click(screen.getByRole("checkbox"))
    fireEvent.click(screen.getByRole("button", { name: "注册并登录" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("两次输入的密码不一致")
    expect(mocks.register).not.toHaveBeenCalled()
  })

  it("注册提交邮箱、密码和条款确认并进入工作台", async () => {
    mocks.register.mockResolvedValue(undefined)
    render(<RegisterPage />)
    fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "person@example.com" } })
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "StrongPassword!2026" } })
    fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "StrongPassword!2026" } })
    fireEvent.click(screen.getByRole("checkbox"))
    fireEvent.click(screen.getByRole("button", { name: "注册并登录" }))

    await waitFor(() => {
      expect(mocks.register).toHaveBeenCalledWith("person@example.com", "StrongPassword!2026", true)
      expect(mocks.replace).toHaveBeenCalledWith("/")
    })
  })

  it("登录展示稳定错误信息并拒绝外部 next 跳转", async () => {
    mocks.login.mockRejectedValueOnce(new AuthApiError(
      401,
      "INVALID_CREDENTIALS",
      "账号或密码错误",
    ))
    window.history.replaceState({}, "", "/login?next=//evil.example")
    render(<LoginPage />)
    fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "person@example.com" } })
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "WrongPassword!2026" } })
    fireEvent.click(screen.getByRole("button", { name: "登录" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("账号或密码错误")

    mocks.login.mockResolvedValueOnce(undefined)
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "StrongPassword!2026" } })
    fireEvent.click(screen.getByRole("button", { name: "登录" }))
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith("/"))
  })
})
