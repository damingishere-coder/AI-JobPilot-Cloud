import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import JobDetailPage from "@/app/jobs/detail/page"
import JobsPage from "@/app/jobs/page"
import type { JobDetail, JobSummary, MatchView } from "@/lib/cloudTypes"

const mocks = vi.hoisted(() => ({
  secureRequest: vi.fn(),
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ secureRequest: mocks.secureRequest }),
}))

vi.mock("@/app/components/PageHeader", () => ({
  default: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

const jobId = "11111111-1111-4111-8111-111111111111"
const matchId = "22222222-2222-4222-8222-222222222222"
const taskId = "33333333-3333-4333-8333-333333333333"

function jobSummary(overrides: Partial<JobSummary> = {}): JobSummary {
  return {
    id: jobId,
    platform: "BOSS",
    title: "Java 后端开发",
    companyName: "示例科技",
    salary: { minK: 20, maxK: 35, months: 14, text: null },
    location: "上海",
    status: "ACTIVE",
    latestMatchSummary: {
      id: matchId, score: 82, decision: "APPLY", greeting: null,
      status: "SUCCEEDED", completedAt: "2026-08-13T07:05:00Z",
    },
    deliveryTaskStatus: {
      id: taskId, status: "PENDING_CONFIRMATION",
      createdAt: "2026-08-13T07:06:00Z", confirmedAt: null,
    },
    lastSeenAt: "2026-08-13T07:00:00Z",
    ...overrides,
  }
}

function matchView(overrides: Partial<MatchView> = {}): MatchView {
  return {
    id: matchId, jobId, resumeId: null, preferenceId: null,
    status: "SUCCEEDED", score: 82, decision: "REVIEW",
    summary: "岗位与简历匹配度较高，公司在做企业级 SaaS。",
    strengths: ["技术栈匹配", "行业经验对口"],
    risks: ["薪资区间略低于预期"],
    greeting: "您好，我对贵司岗位很感兴趣",
    priorityCompany: null, model: null, usage: null, error: null,
    attemptCount: 1, createdAt: "2026-08-13T07:00:00Z", completedAt: "2026-08-13T07:05:00Z",
    ...overrides,
  }
}

function jobDetail(overrides: Partial<JobDetail> = {}): JobDetail {
  return {
    id: jobId, platform: "BOSS", externalJobId: "ext-1",
    title: "Java 后端开发", companyName: "示例科技",
    salary: { minK: 20, maxK: 35, months: 14, text: "20-35K·14薪" },
    location: "上海", experience: "3-5年", degree: "本科",
    description: "负责后端服务开发。",
    jobUrl: "https://www.zhipin.com/job_detail/xxx",
    companyInfo: null, skills: ["Java"], welfare: ["五险一金"],
    status: "ACTIVE", capturedAt: "2026-08-13T07:00:00Z", lastSeenAt: "2026-08-13T07:00:00Z",
    latestMatch: null, deliveryTask: null,
    ...overrides,
  }
}

function emptyPage() {
  return { items: [], page: 1, size: 20, total: 0, hasNext: false }
}

function idempotencyKeyOf(call: unknown[]): string {
  const init = call[1] as RequestInit
  return (init.headers as Record<string, string>)["Idempotency-Key"] ?? ""
}

describe("第五轮统一岗位池", () => {
  beforeEach(() => {
    mocks.secureRequest.mockReset()
    window.history.replaceState({}, "", "/jobs")
  })

  it("用单次列表响应展示分数/推荐等级/匹配与投递状态，不逐行请求", async () => {
    mocks.secureRequest.mockResolvedValue({
      items: [
        jobSummary(),
        jobSummary({
          id: "44444444-4444-4444-8444-444444444444",
          title: "前端开发",
          latestMatchSummary: {
            id: "55555555-5555-4555-8555-555555555555", score: 55, decision: "REVIEW",
            greeting: null, status: "SUCCEEDED", completedAt: "2026-08-13T07:05:00Z",
          },
          deliveryTaskStatus: null,
        }),
      ],
      page: 1, size: 20, total: 2, hasNext: false,
    })

    render(<JobsPage />)

    const row = (await screen.findByText("Java 后端开发")).closest("tr")
    expect(row).not.toBeNull()
    const scope = within(row!)
    expect(scope.getByText("82 分")).toBeInTheDocument()
    expect(scope.getByText("推荐投递")).toBeInTheDocument()
    expect(scope.getByText("已完成")).toBeInTheDocument()
    expect(scope.getByText("待确认")).toBeInTheDocument()
    // 没有逐行 match/detail 请求：只调用一次列表接口
    expect(mocks.secureRequest).toHaveBeenCalledTimes(1)
    expect(String(mocks.secureRequest.mock.calls[0][0])).toContain("/api/jobs?")
  })

  it("推荐等级筛选回到第 1 页并带对应查询参数", async () => {
    mocks.secureRequest.mockResolvedValue({ ...emptyPage(), items: [jobSummary()], total: 1 })

    render(<JobsPage />)
    await screen.findByText("Java 后端开发")

    fireEvent.change(screen.getByLabelText("推荐等级"), { target: { value: "REVIEW" } })

    await waitFor(() => {
      const path = String(mocks.secureRequest.mock.calls.at(-1)?.[0])
      expect(path).toContain("matchDecision=REVIEW")
      expect(path).toContain("page=1")
    })
  })

  it("FAILED 岗位重新分析使用 force=true 与新幂等键", async () => {
    mocks.secureRequest.mockResolvedValue({
      ...emptyPage(),
      items: [jobSummary({
        latestMatchSummary: {
          id: matchId, score: null, decision: null, greeting: null,
          status: "FAILED", completedAt: null,
        },
      })],
      total: 1,
    })

    render(<JobsPage />)
    await screen.findByText("Java 后端开发")

    fireEvent.click(screen.getByRole("button", { name: /重新分析/ }))

    await waitFor(() => {
      const call = mocks.secureRequest.mock.calls.find(([path]) => String(path).includes("/analyze"))
      expect(call).toBeTruthy()
      expect(call![1]?.method).toBe("POST")
      expect(JSON.parse(String(call![1]?.body))).toEqual({ force: true })
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })
  })

  it("未分析岗位提交分析后展示排队状态并禁止重复点击", async () => {
    mocks.secureRequest
      .mockResolvedValueOnce({
        ...emptyPage(),
        items: [jobSummary({ latestMatchSummary: null, deliveryTaskStatus: null })],
        total: 1,
      })
      .mockResolvedValueOnce({
        matchId, jobId, status: "PENDING", queuedAt: "2026-08-13T07:10:00Z", reusedExisting: false,
      })

    render(<JobsPage />)
    fireEvent.click(await screen.findByRole("button", { name: /AI 分析/ }))

    expect(await screen.findByText("排队中")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /AI 分析|重新分析/ })).not.toBeInTheDocument()
    expect(screen.getByText(/分析排队中，请稍后手动刷新/)).toBeInTheDocument()
  })
})

describe("第五轮岗位详情完整分析", () => {
  beforeEach(() => {
    mocks.secureRequest.mockReset()
    window.history.replaceState({}, "", `/jobs/detail?id=${jobId}`)
  })

  it("成功展示 summary/strengths/risks 与招呼语预览；REVIEW 可手动加入投递清单", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      if (path === `/api/jobs/${jobId}`) return jobDetail({ latestMatch: matchView() })
      if (path === "/api/delivery/tasks") {
        return {
          id: taskId, jobPostId: jobId, jobMatchId: matchId,
          status: "WAITING_CONFIRM", greeting: "您好，我对贵司岗位很感兴趣",
          version: 1, confirmationVersion: 0, confirmedAt: null, createdAt: "2026-08-13T07:06:00Z",
        }
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<JobDetailPage />)

    expect(await screen.findByText("岗位与简历匹配度较高，公司在做企业级 SaaS。")).toBeInTheDocument()
    expect(screen.getByText("技术栈匹配")).toBeInTheDocument()
    expect(screen.getByText("薪资区间略低于预期")).toBeInTheDocument()
    expect(screen.getByText("您好，我对贵司岗位很感兴趣")).toBeInTheDocument()
    expect(screen.getByText("谨慎投递", { selector: "span" })).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: /加入投递清单/ }))

    await waitFor(() => {
      const call = mocks.secureRequest.mock.calls.find(([path]) => path === "/api/delivery/tasks")
      expect(call).toBeTruthy()
      expect(call![1]?.method).toBe("POST")
      expect(JSON.parse(String(call![1]?.body))).toEqual({ jobPostId: jobId, jobMatchId: matchId })
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })
    expect(await screen.findByText("已加入投递清单，请前往投递清单逐条确认")).toBeInTheDocument()
    expect(screen.getByText("进入投递清单")).toBeInTheDocument()
  })

  it("APPLY 展示自动加入说明，不提供手动创建按钮", async () => {
    mocks.secureRequest.mockResolvedValue(jobDetail({ latestMatch: matchView({ decision: "APPLY" }) }))

    render(<JobDetailPage />)

    expect(await screen.findByText("推荐投递", { selector: "span" })).toBeInTheDocument()
    expect(screen.getByText(/自动加入待确认清单/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /加入投递清单/ })).not.toBeInTheDocument()
    expect(mocks.secureRequest).toHaveBeenCalledTimes(1)
  })

  it("SKIP 说明不会自动创建任务", async () => {
    mocks.secureRequest.mockResolvedValue(jobDetail({ latestMatch: matchView({ decision: "SKIP" }) }))

    render(<JobDetailPage />)

    expect(await screen.findByText("不建议投递", { selector: "span" })).toBeInTheDocument()
    expect(screen.getByText(/不会自动创建投递任务/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /加入投递清单/ })).not.toBeInTheDocument()
  })

  it("FAILED 展示错误信息并允许 force=true 重新排队", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      if (path === `/api/jobs/${jobId}`) {
        return jobDetail({
          latestMatch: matchView({
            status: "FAILED", score: null, decision: null, summary: null,
            strengths: [], risks: [], greeting: null,
            error: { code: "AI_UNAVAILABLE", message: "AI 服务暂不可用" },
          }),
        })
      }
      if (path === `/api/jobs/${jobId}/analyze`) {
        return { matchId, jobId, status: "PENDING", queuedAt: "2026-08-13T07:10:00Z", reusedExisting: false }
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<JobDetailPage />)

    expect(await screen.findByText(/AI 服务暂不可用/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: /重新分析/ }))

    await waitFor(() => {
      const call = mocks.secureRequest.mock.calls.find(([path]) => String(path).includes("/analyze"))
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ force: true })
    })
    expect(await screen.findByText(/排队中/)).toBeInTheDocument()
  })
})
