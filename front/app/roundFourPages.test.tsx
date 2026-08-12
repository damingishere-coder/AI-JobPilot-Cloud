import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import JobsPage from "@/app/jobs/page"
import PreferencesPage from "@/app/preferences/page"
import ResumePage from "@/app/resume/page"
import { AuthApiError } from "@/lib/authApi"

const mocks = vi.hoisted(() => ({
  secureRequest: vi.fn(),
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ secureRequest: mocks.secureRequest }),
}))

vi.mock("@/app/components/PageHeader", () => ({
  default: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

const emptyResumePage = { items: [], page: 1, size: 20, total: 0, hasNext: false }
const emptyJobsPage = { items: [], page: 1, size: 20, total: 0, hasNext: false }

describe("第四轮 Cloud 页面", () => {
  beforeEach(() => {
    mocks.secureRequest.mockReset()
    vi.spyOn(window, "confirm").mockReturnValue(true)
    vi.stubGlobal("crypto", { randomUUID: () => "11111111-1111-4111-8111-111111111111" })
  })

  it("简历页会提交文件、幂等键并重新读取解析状态", async () => {
    mocks.secureRequest.mockImplementation(async (path: string, init?: RequestInit) => {
      if (path === "/api/resumes/current?includeExtractedText=true") return null
      if (path === "/api/resumes?page=1&size=20") return emptyResumePage
      if (path === "/api/resumes/upload?setCurrent=true") {
        expect(init?.method).toBe("POST")
        expect((init?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy()
        expect(init?.body).toBeInstanceOf(FormData)
        return { resume: {}, deduplicated: false }
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<ResumePage />)
    await screen.findByText("上传简历后，解析状态和只读文本会显示在这里。")
    const fileInput = screen.getByLabelText("选择简历文件")
    fireEvent.change(fileInput, {
      target: { files: [new File(["个人简历"], "resume.txt", { type: "text/plain" })] },
    })
    fireEvent.click(screen.getByRole("button", { name: "上传并设为当前简历" }))

    await waitFor(() => {
      expect(mocks.secureRequest).toHaveBeenCalledWith(
        "/api/resumes/upload?setCurrent=true",
        expect.objectContaining({ method: "POST", body: expect.any(FormData) }),
      )
    })
  })

  it("求职目标版本冲突后加载服务端最新版并保留明确提示", async () => {
    const original = {
      id: "pref-1", version: 1, targetTitles: ["Java 开发"], cities: ["上海"],
      salaryMinK: 20, salaryMaxK: 35, experienceLevels: [], degreeLevels: [],
      industries: [], companyScales: [], preferredCompanies: [], excludedCompanies: [],
      excludedKeywords: [], extraFilters: {}, updatedAt: "2026-08-12T00:00:00Z",
    }
    const latest = { ...original, version: 2, targetTitles: ["后端开发"] }
    mocks.secureRequest
      .mockResolvedValueOnce(original)
      .mockRejectedValueOnce(new AuthApiError(409, "RESOURCE_VERSION_CONFLICT", "版本冲突"))
      .mockResolvedValueOnce(latest)

    render(<PreferencesPage />)
    expect(await screen.findByDisplayValue("Java 开发")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "保存求职目标" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("已重新加载最新版本")
    expect(screen.getByDisplayValue("后端开发")).toBeInTheDocument()
    expect(mocks.secureRequest).toHaveBeenNthCalledWith(
      2,
      "/api/preferences",
      expect.objectContaining({ method: "PUT" }),
    )
  })

  it("岗位池在没有数据时展示安全的空状态", async () => {
    mocks.secureRequest.mockResolvedValue(emptyJobsPage)

    render(<JobsPage />)

    expect(await screen.findByText("岗位池目前为空")).toBeInTheDocument()
    expect(screen.getByText("共 0 条，仅显示当前用户数据")).toBeInTheDocument()
    expect(mocks.secureRequest).toHaveBeenCalledWith(expect.stringContaining("/api/jobs?"))
  })
})
