import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import DeliveryPage from "@/app/delivery/page"
import { ERROR_CODE_LABELS } from "@/lib/cloudTypes"
import type { DeliverySummary, TaskDetail, TaskListItem } from "@/lib/cloudTypes"

const mocks = vi.hoisted(() => ({
  secureRequest: vi.fn(),
  sendCloudDeliveryWake: vi.fn(),
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ secureRequest: mocks.secureRequest }),
}))

vi.mock("@/lib/chromeBridge", () => ({
  sendCloudDeliveryWake: (...args: unknown[]) => mocks.sendCloudDeliveryWake(...args),
}))

vi.mock("@/app/components/PageHeader", () => ({
  default: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

const jobId = "11111111-1111-4111-8111-111111111111"
const matchId = "22222222-2222-4222-8222-222222222222"
const taskId = "33333333-3333-4333-8333-333333333333"

function taskListItem(overrides: Partial<TaskListItem> = {}): TaskListItem {
  return {
    id: taskId,
    status: "WAITING_CONFIRM",
    greeting: "您好，我对贵司岗位很感兴趣",
    version: 2,
    confirmationVersion: 0,
    confirmedAt: null,
    job: {
      id: jobId, platform: "BOSS", title: "Java 后端开发", companyName: "示例科技",
      jobUrl: "https://www.zhipin.com/job_detail/xxx",
      salary: { minK: 25, maxK: 40, months: 14, text: null },
      location: "杭州",
    },
    match: {
      id: matchId, score: 82, decision: "REVIEW",
      summary: "岗位与简历匹配度较高，公司在做企业级 SaaS。",
      strengths: ["技术栈匹配"],
      risks: ["薪资区间略低于预期"],
    },
    device: null,
    lastEvent: { id: 1, eventType: "CREATED", fromStatus: null, toStatus: "WAITING_CONFIRM", actorType: "SYSTEM", createdAt: "2026-08-13T07:00:00Z", details: null },
    createdAt: "2026-08-13T07:00:00Z",
    updatedAt: "2026-08-13T07:00:00Z",
    ...overrides,
  }
}

function taskDetail(overrides: Partial<TaskDetail> = {}): TaskDetail {
  return {
    id: taskId,
    jobPostId: jobId,
    jobMatchId: matchId,
    status: "WAITING_CONFIRM",
    greeting: "您好，我对贵司岗位很感兴趣",
    version: 2,
    confirmationVersion: 0,
    confirmedAt: null,
    assignedDeviceId: null,
    attemptCount: 0,
    lastError: null,
    startedAt: null,
    finishedAt: null,
    createdAt: "2026-08-13T07:00:00Z",
    updatedAt: "2026-08-13T07:00:00Z",
    job: {
      id: jobId, platform: "BOSS", title: "Java 后端开发", companyName: "示例科技",
      jobUrl: "https://www.zhipin.com/job_detail/xxx",
      salary: { minK: 25, maxK: 40, months: 14, text: null },
      location: "杭州",
    },
    match: {
      id: matchId, score: 82, decision: "REVIEW",
      summary: "岗位与简历匹配度较高，公司在做企业级 SaaS。",
      strengths: ["技术栈匹配"],
      risks: ["薪资区间略低于预期"],
    },
    device: null,
    events: [
      { id: 1, eventType: "CREATED", fromStatus: null, toStatus: "WAITING_CONFIRM", actorType: "SYSTEM", createdAt: "2026-08-13T07:00:00Z", details: null },
    ],
    ...overrides,
  }
}

const listPage = {
  items: [taskListItem()],
  page: 1, size: 20, total: 1, hasNext: false,
}

let confirmIssued = false

const summary: DeliverySummary = {
  waitingConfirm: 1, confirmed: 0, pulledByPlugin: 0, running: 0,
  success: 0, failed: 0, skipped: 0, pausedNeedUser: 0, total: 1,
}

function routeRequests() {
  confirmIssued = false
  mocks.secureRequest.mockImplementation(async (path: string, init?: RequestInit) => {
    const url = String(path)
    if (url.startsWith("/api/delivery/tasks/summary")) return summary
    if (url.startsWith("/api/delivery/tasks?")) return listPage
    if (url === `/api/delivery/tasks/${taskId}`) {
      return taskDetail(confirmIssued
        ? { status: "CONFIRMED", confirmedAt: "2026-08-13T08:30:00Z" }
        : {})
    }
    if (url === `/api/delivery/tasks/${taskId}/confirm`) {
      confirmIssued = true
      return { id: taskId, status: "CONFIRMED", confirmationVersion: 1, confirmedAt: "2026-08-13T08:30:00Z", assignedDeviceId: null, version: 3 }
    }
    if (url === `/api/delivery/tasks/${taskId}/greeting`) {
      return { id: taskId, greeting: init ? JSON.parse(String(init.body)).greeting : null, status: "WAITING_CONFIRM", confirmationRequired: true, version: 3 }
    }
    if (url === `/api/delivery/tasks/${taskId}/skip`) {
      return { id: taskId, status: "SKIPPED", finishedAt: "2026-08-13T08:30:00Z", version: 3 }
    }
    throw new Error(`意外请求：${path}`)
  })
}

async function renderWithDetailSelected() {
  render(<DeliveryPage />)
  fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
  await screen.findByText("岗位与简历匹配度较高，公司在做企业级 SaaS。")
}

function findCall(method: "POST" | "PUT", pathPart: string) {
  return mocks.secureRequest.mock.calls.find(([path, init]) => String(path).includes(pathPart) && init?.method === method)
}

function idempotencyKeyOf(call: unknown[]): string {
  const init = call[1] as RequestInit
  return (init.headers as Record<string, string>)["Idempotency-Key"] ?? ""
}

describe("第六轮投递清单（P6 确认闭环）", () => {
  it("错误码展示只保留规范八类，触发类原因不复用该映射", () => {
    expect(Object.keys(ERROR_CODE_LABELS).sort()).toEqual([
      "BUTTON_NOT_FOUND", "CAPTCHA_REQUIRED", "JOB_EXPIRED", "LOGIN_REQUIRED",
      "NETWORK_ERROR", "PAGE_STRUCTURE_CHANGED", "RISK_CONTROL", "UNKNOWN_ERROR",
    ])
    // 触发类原因不会持久化到 last_error_code，展示时回退原值而不是伪造标签。
    expect(ERROR_CODE_LABELS["USER_REQUESTED"]).toBeUndefined()
    expect(ERROR_CODE_LABELS["FAILURE_THRESHOLD"]).toBeUndefined()
    expect(ERROR_CODE_LABELS["MAX_ATTEMPTS_EXCEEDED"]).toBeUndefined()
  })

  beforeEach(() => {
    mocks.secureRequest.mockReset()
    mocks.sendCloudDeliveryWake.mockReset()
    routeRequests()
  })

  it("全局统计来自 /summary 且按 P6 状态筛选，列表展示薪资与推荐理由", async () => {
    render(<DeliveryPage />)
    await screen.findByRole("button", { name: "Java 后端开发" })

    // 全局统计口径说明 + 服务端 summary 数值
    expect(await screen.findByText(/状态概览为全局统计（共 1 条）/)).toBeInTheDocument()
    expect(screen.getByText("杭州", { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/25-40K/)).toBeInTheDocument()
    expect(screen.getByText("待确认", { selector: "span" })).toBeInTheDocument()

    // 点击统计卡按 P6 状态名筛选
    fireEvent.click(screen.getByRole("button", { name: "状态筛选：待确认" }))
    await waitFor(() => {
      const path = String(mocks.secureRequest.mock.calls.at(-1)?.[0])
      expect(path).toContain("status=WAITING_CONFIRM")
    })

    // AI 推荐筛选下拉按 recommendation 参数请求
    fireEvent.change(screen.getByLabelText("AI 推荐"), { target: { value: "REVIEW" } })
    await waitFor(() => {
      const call = mocks.secureRequest.mock.calls.find(([path]) => String(path).includes("recommendation=REVIEW"))
      expect(call).toBeTruthy()
    })
  })

  it("详情直接展示 AI 推荐理由，不再请求 /api/jobs/{id}/match；无设备管理与批量按钮", async () => {
    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))

    expect(await screen.findByText("岗位与简历匹配度较高，公司在做企业级 SaaS。")).toBeInTheDocument()
    expect(screen.getByText("技术栈匹配")).toBeInTheDocument()
    expect(screen.getByText("薪资区间略低于预期")).toBeInTheDocument()
    expect(screen.getByText("AI 推荐理由")).toBeInTheDocument()

    // 不再依赖岗位页的完整分析接口
    expect(mocks.secureRequest.mock.calls.some(([path]) => String(path).includes("/api/jobs/"))).toBe(false)
    // 不出现设备绑定/批量操作 UI
    expect(screen.queryByText(/插件绑定与设备管理/)).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /批量|一键|唤醒/ })).not.toBeInTheDocument()
    // 执行能力说明可见
    expect(screen.getByText(/浏览器插件会在你显式开始/)).toBeInTheDocument()
  })

  it("逐条确认只发送 version 与 acknowledged，成功后唤醒插件并展示接收结果", async () => {
    mocks.sendCloudDeliveryWake.mockResolvedValue({
      success: true, accepted: true, taskId, state: "accepted", code: "ACCEPTED", message: "插件已接收投递唤醒请求",
    })
    await renderWithDetailSelected()

    fireEvent.click(screen.getByRole("button", { name: "确认投递" }))
    fireEvent.click(screen.getByLabelText(/我已确认岗位信息与招呼语/))
    fireEvent.click(screen.getByRole("button", { name: "确认投递" }))

    await waitFor(() => {
      const call = findCall("POST", "/confirm")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ version: 2, acknowledged: true })
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })
    expect(await screen.findByText(/插件已接收执行请求/)).toBeInTheDocument()
    expect(screen.getByText("已确认", { selector: "span" })).toBeInTheDocument()
    // 确认成功必须显式唤醒一次该 taskId
    expect(mocks.sendCloudDeliveryWake).toHaveBeenCalledTimes(1)
    expect(mocks.sendCloudDeliveryWake).toHaveBeenCalledWith(taskId, 3000)
  })

  it("插件未接收时提示可稍后重试，不静默轮询", async () => {
    mocks.sendCloudDeliveryWake.mockResolvedValue({
      success: false, accepted: false, taskId, code: "EXTENSION_UNAVAILABLE", message: "插件未响应投递唤醒请求",
    })
    await renderWithDetailSelected()

    fireEvent.click(screen.getByRole("button", { name: "确认投递" }))
    fireEvent.click(screen.getByLabelText(/我已确认岗位信息与招呼语/))
    fireEvent.click(screen.getByRole("button", { name: "确认投递" }))

    expect(await screen.findByText(/插件暂未接收执行请求/)).toBeInTheDocument()
    expect(mocks.sendCloudDeliveryWake).toHaveBeenCalledTimes(1)
  })

  it("逐条跳过使用当前 version、原因与新幂等键", async () => {
    await renderWithDetailSelected()

    fireEvent.click(screen.getByRole("button", { name: "跳过任务" }))
    fireEvent.change(screen.getByLabelText("跳过原因"), { target: { value: "岗位要求与简历不符" } })
    fireEvent.click(screen.getByLabelText(/确认跳过该任务/))
    fireEvent.click(screen.getByRole("button", { name: "确认跳过" }))

    await waitFor(() => {
      const call = findCall("POST", "/skip")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ version: 2, reason: "岗位要求与简历不符" })
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })
    expect(await screen.findByText("任务已跳过")).toBeInTheDocument()
  })

  it("BOSS 招呼语可编辑保存并提示需要重新确认；没有批量按钮", async () => {
    await renderWithDetailSelected()

    const greeting = screen.getByLabelText("招呼语") as HTMLTextAreaElement
    expect(greeting).not.toBeDisabled()
    expect(greeting.value).toBe("您好，我对贵司岗位很感兴趣")
    expect(screen.getByText("13/60", { exact: false })).toBeInTheDocument()

    fireEvent.change(greeting, { target: { value: "新的招呼语" } })
    fireEvent.click(screen.getByRole("button", { name: "保存招呼语" }))

    await waitFor(() => {
      const call = findCall("PUT", "/greeting")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ version: 2, greeting: "新的招呼语" })
    })
    expect(await screen.findByText(/需要重新确认投递/)).toBeInTheDocument()
    expect(screen.getAllByText("待确认", { selector: "span" }).length).toBeGreaterThan(0)
    expect(screen.queryByText(/确认于/)).not.toBeInTheDocument()
    // 没有批量操作按钮
    expect(screen.queryByRole("button", { name: /批量|一键/ })).not.toBeInTheDocument()
  })

  it("智联任务招呼语禁用且不发送 greeting API", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks/summary")) return summary
      if (url.startsWith("/api/delivery/tasks?")) {
        return {
          ...listPage,
          items: [taskListItem({ job: { ...taskListItem().job, platform: "ZHILIAN", jobUrl: null } })],
        }
      }
      if (url === `/api/delivery/tasks/${taskId}`) {
        return taskDetail({ job: { ...taskDetail().job, platform: "ZHILIAN", jobUrl: null } })
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText(/智联投递不使用自定义招呼语/)

    const greeting = screen.getByLabelText("招呼语") as HTMLTextAreaElement
    expect(greeting).toBeDisabled()
    expect(screen.queryByRole("button", { name: "保存招呼语" })).not.toBeInTheDocument()
    expect(mocks.secureRequest.mock.calls.some(([path]) => String(path).includes("/greeting"))).toBe(false)
  })

  it("已确认任务展示等待执行说明；已跳过任务只读展示", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks/summary")) return { ...summary, waitingConfirm: 0, confirmed: 1 }
      if (url.startsWith("/api/delivery/tasks?")) {
        return { ...listPage, items: [taskListItem({ status: "CONFIRMED", confirmedAt: "2026-08-13T08:00:00Z" })] }
      }
      if (url === `/api/delivery/tasks/${taskId}`) {
        return taskDetail({ status: "CONFIRMED", confirmedAt: "2026-08-13T08:00:00Z" })
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText("已确认", { selector: "span" })
    expect(screen.getByText(/任务已确认，等待插件领取执行/)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /唤醒/ })).not.toBeInTheDocument()
  })

  it("需用户处理的任务展示失败/暂停原因与处理指引", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks/summary")) {
        return { ...summary, waitingConfirm: 0, pausedNeedUser: 1 }
      }
      if (url.startsWith("/api/delivery/tasks?")) {
        return { ...listPage, items: [taskListItem({ status: "PAUSED_NEED_USER" })] }
      }
      if (url === `/api/delivery/tasks/${taskId}`) {
        return taskDetail({
          status: "PAUSED_NEED_USER",
          confirmedAt: "2026-08-13T08:00:00Z",
          attemptCount: 1,
          lastError: { code: "CAPTCHA_REQUIRED", message: "请完成滑块验证后重试", retryable: false },
        })
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText("需用户处理", { selector: "span" })
    expect(screen.getByText("请完成滑块验证后重试", { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/插件绝不绕过验证或自动登录/)).toBeInTheDocument()
    // 暂停任务可重新确认或跳过
    expect(screen.getByRole("button", { name: "确认投递" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "跳过任务" })).toBeInTheDocument()
  })

  it("可重试的失败任务允许重新确认投递", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks/summary")) {
        return { ...summary, waitingConfirm: 0, failed: 1 }
      }
      if (url.startsWith("/api/delivery/tasks?")) {
        return { ...listPage, items: [taskListItem({ status: "FAILED" })] }
      }
      if (url === `/api/delivery/tasks/${taskId}`) {
        return taskDetail({
          status: "FAILED",
          confirmedAt: "2026-08-13T08:00:00Z",
          attemptCount: 1,
          lastError: { code: "NETWORK_ERROR", message: "网络或页面加载异常", retryable: true },
        })
      }
      throw new Error(`意外请求：${path}`)
    })

    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText("投递失败", { selector: "span" })
    expect(screen.getByText(/该失败可重试：处理页面问题后可重新确认投递/)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "确认投递" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "跳过任务" })).toBeInTheDocument()
  })

  it("终态失败（岗位过期）不允许重新确认，只能跳过", async () => {
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks/summary")) return { ...summary, waitingConfirm: 0, failed: 1 }
      if (url.startsWith("/api/delivery/tasks?")) {
        return { ...listPage, items: [taskListItem({ status: "FAILED" })] }
      }
      if (url === `/api/delivery/tasks/${taskId}`) {
        return taskDetail({
          status: "FAILED",
          confirmedAt: "2026-08-13T08:00:00Z",
          attemptCount: 1,
          lastError: { code: "JOB_EXPIRED", message: "该岗位已停止招聘", retryable: false },
        })
      }
      throw new Error(`意外请求：${path}`)
    })
    render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText("该岗位已停止招聘", { exact: false })
    expect(screen.queryByRole("button", { name: "确认投递" })).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: "跳过任务" })).toBeInTheDocument()
  })
})
