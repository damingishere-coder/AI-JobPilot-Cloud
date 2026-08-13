import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import DeliveryPage from "@/app/delivery/page"
import type { CloudDeliveryEvent, CloudWakeResult } from "@/lib/chromeBridge"
import type { DeviceView, MatchView, TaskDetail, TaskListItem } from "@/lib/cloudTypes"

const mocks = vi.hoisted(() => ({
  secureRequest: vi.fn(),
  sendCloudDeliveryWake: vi.fn(),
  subscribeChromeBridgeEvents: vi.fn(),
  parseCloudDeliveryEvent: undefined as unknown as typeof import("@/lib/chromeBridge").parseCloudDeliveryEvent,
}))

vi.mock("@/app/components/AuthProvider", () => ({
  useAuth: () => ({ secureRequest: mocks.secureRequest }),
}))

vi.mock("@/app/components/PageHeader", () => ({
  default: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

vi.mock("@/lib/chromeBridge", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/chromeBridge")>()
  mocks.parseCloudDeliveryEvent = actual.parseCloudDeliveryEvent
  return {
    ...actual,
    sendCloudDeliveryWake: mocks.sendCloudDeliveryWake,
    subscribeChromeBridgeEvents: mocks.subscribeChromeBridgeEvents,
  }
})

const jobId = "11111111-1111-4111-8111-111111111111"
const matchId = "22222222-2222-4222-8222-222222222222"
const taskId = "33333333-3333-4333-8333-333333333333"
const deviceId = "66666666-6666-4666-8666-666666666666"

function matchView(overrides: Partial<MatchView> = {}): MatchView {
  return {
    id: matchId, jobId, resumeId: null, preferenceId: null,
    status: "SUCCEEDED", score: 82, decision: "REVIEW",
    summary: "岗位与简历匹配度较高，公司在做企业级 SaaS。",
    strengths: ["技术栈匹配"],
    risks: ["薪资区间略低于预期"],
    greeting: "您好，我对贵司岗位很感兴趣",
    priorityCompany: null, model: null, usage: null, error: null,
    attemptCount: 1, createdAt: "2026-08-13T07:00:00Z", completedAt: "2026-08-13T07:05:00Z",
    ...overrides,
  }
}

function taskListItem(overrides: Partial<TaskListItem> = {}): TaskListItem {
  return {
    id: taskId,
    status: "PENDING_CONFIRMATION",
    greeting: "您好，我对贵司岗位很感兴趣",
    version: 2,
    confirmationVersion: 0,
    confirmedAt: null,
    job: { id: jobId, platform: "BOSS", title: "Java 后端开发", companyName: "示例科技", jobUrl: "https://www.zhipin.com/job_detail/xxx" },
    match: { id: matchId, score: 82, decision: "REVIEW" },
    device: null,
    lastEvent: { id: 1, eventType: "CREATED", fromStatus: null, toStatus: "PENDING_CONFIRMATION", actorType: "SYSTEM", createdAt: "2026-08-13T07:00:00Z", details: null },
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
    status: "PENDING_CONFIRMATION",
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
    job: { id: jobId, platform: "BOSS", title: "Java 后端开发", companyName: "示例科技", jobUrl: "https://www.zhipin.com/job_detail/xxx" },
    match: { id: matchId, score: 82, decision: "REVIEW" },
    device: null,
    events: [
      { id: 1, eventType: "CREATED", fromStatus: null, toStatus: "PENDING_CONFIRMATION", actorType: "SYSTEM", createdAt: "2026-08-13T07:00:00Z", details: null },
    ],
    ...overrides,
  }
}

function activeDevice(overrides: Partial<DeviceView> = {}): DeviceView {
  return {
    id: deviceId,
    deviceName: "办公电脑",
    browserName: "Chrome",
    browserVersion: "127.0",
    extensionVersion: "1.2.0",
    status: "ACTIVE",
    capabilities: ["BOSS", "ZHILIAN"],
    lastSeenAt: "2026-08-13T08:00:00Z",
    boundAt: "2026-08-12T08:00:00Z",
    revokedAt: null,
    revokeReason: null,
    ...overrides,
  }
}

const listPage = {
  items: [taskListItem()],
  page: 1, size: 20, total: 1, hasNext: false,
}

function routeRequests() {
  mocks.secureRequest.mockImplementation(async (path: string, init?: RequestInit) => {
    const url = String(path)
    if (url.startsWith("/api/delivery/tasks?")) return listPage
    if (url === `/api/delivery/tasks/${taskId}`) return taskDetail()
    if (url === `/api/jobs/${jobId}/match`) return matchView()
    if (url === "/api/plugin/devices") return [activeDevice()]
    if (url === "/api/plugin/bind-code") {
      return { bindCode: "ABCD-1234", expiresAt: "2026-08-13T08:40:00Z", expiresInSeconds: 300 }
    }
    if (url === `/api/delivery/tasks/${taskId}/confirm`) {
      return { id: taskId, status: "CONFIRMED", confirmationVersion: 1, confirmedAt: "2026-08-13T08:30:00Z", assignedDeviceId: null, version: 3 }
    }
    if (url === `/api/delivery/tasks/${taskId}/greeting`) {
      return { id: taskId, greeting: init ? JSON.parse(String(init.body)).greeting : null, status: "CONFIRMED", confirmationRequired: true, version: 3 }
    }
    if (url === `/api/delivery/tasks/${taskId}/skip`) {
      return { id: taskId, status: "SKIPPED", finishedAt: "2026-08-13T08:30:00Z", version: 3 }
    }
    if (url === `/api/plugin/devices/${deviceId}/revoke`) {
      return { id: deviceId, status: "REVOKED", revokedAt: "2026-08-13T08:30:00Z" }
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

describe("第五轮投递清单", () => {
  beforeEach(() => {
    mocks.secureRequest.mockReset()
    mocks.sendCloudDeliveryWake.mockReset()
    mocks.subscribeChromeBridgeEvents.mockReset()
    mocks.subscribeChromeBridgeEvents.mockImplementation(() => () => {})
    routeRequests()
  })

  it("BOSS 招呼语可编辑保存并提示需要重新确认；没有批量按钮", async () => {
    await renderWithDetailSelected()

    const greeting = screen.getByLabelText("招呼语") as HTMLTextAreaElement
    expect(greeting).not.toBeDisabled()
    expect(greeting.value).toBe("您好，我对贵司岗位很感兴趣")
    expect(screen.getByText("3/60", { exact: false })).toBeInTheDocument()

    fireEvent.change(greeting, { target: { value: "新的招呼语" } })
    fireEvent.click(screen.getByRole("button", { name: "保存招呼语" }))

    await waitFor(() => {
      const call = findCall("PUT", "/greeting")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ version: 2, greeting: "新的招呼语" })
    })
    expect(await screen.findByText(/需要重新确认投递/)).toBeInTheDocument()
    expect(screen.getByText("待确认", { selector: "span" })).toBeInTheDocument()
    expect(screen.queryByText(/确认于/)).not.toBeInTheDocument()
    expect(screen.queryByText(/设备：/)).not.toBeInTheDocument()
    // 没有批量操作按钮
    expect(screen.queryByRole("button", { name: /批量|一键/ })).not.toBeInTheDocument()
  })

  it("智联任务招呼语禁用且不发送 greeting API", async () => {
    const zhilianDetail = taskDetail({
      job: { id: jobId, platform: "ZHILIAN", title: "Java 后端开发", companyName: "示例科技", jobUrl: null },
    })
    mocks.secureRequest.mockImplementation(async (path: string) => {
      const url = String(path)
      if (url.startsWith("/api/delivery/tasks?")) {
        return { ...listPage, items: [taskListItem({ job: { id: jobId, platform: "ZHILIAN", title: "Java 后端开发", companyName: "示例科技", jobUrl: null } })] }
      }
      if (url === `/api/delivery/tasks/${taskId}`) return zhilianDetail
      if (url === `/api/jobs/${jobId}/match`) return matchView()
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

  it("逐条确认使用当前 version 与新幂等键；confirm 成功但插件未接收时保持 CONFIRMED，重新唤醒不再调用 confirm", async () => {
    mocks.sendCloudDeliveryWake.mockResolvedValue({
      success: false, accepted: false, taskId, state: "unbound", code: "PLUGIN_NOT_BOUND", message: "插件尚未绑定云端设备，请点击扩展图标完成绑定",
    } satisfies CloudWakeResult)
    await renderWithDetailSelected()

    fireEvent.click(screen.getByRole("button", { name: /确认并唤醒插件/ }))
    fireEvent.click(screen.getByLabelText(/我已确认岗位信息与招呼语/))
    fireEvent.click(screen.getByRole("button", { name: "确认投递" }))

    await waitFor(() => {
      const call = findCall("POST", "/confirm")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ version: 2, acknowledged: true, assignedDeviceId: null })
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })
    // 确认成功但插件未接收：任务仍为已确认，且提供重新唤醒
    expect(await screen.findByText(/任务已确认，但插件暂未接收/)).toBeInTheDocument()
    expect(screen.getByText("已确认", { selector: "span" })).toBeInTheDocument()
    expect(mocks.sendCloudDeliveryWake).toHaveBeenCalledWith(taskId)

    fireEvent.click(screen.getByRole("button", { name: "重新唤醒插件" }))
    await waitFor(() => expect(mocks.sendCloudDeliveryWake).toHaveBeenCalledTimes(2))
    // 重新唤醒只发 Bridge 消息，不再调用 confirm
    expect(mocks.secureRequest.mock.calls.filter(([path]) => String(path).includes("/confirm"))).toHaveLength(1)
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

  it("扩展 Cloud 事件更新当前任务进度，明确阶段刷新，卸载后取消订阅", async () => {
    const unsubscribe = vi.fn()
    const eventHandler = vi.fn<(event: { payload?: unknown }) => void>()
    mocks.subscribeChromeBridgeEvents.mockImplementation((handler) => {
      eventHandler.mockImplementation(handler as (event: { payload?: unknown }) => void)
      return unsubscribe
    })

    const { unmount } = render(<DeliveryPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Java 后端开发" }))
    await screen.findByText("岗位与简历匹配度较高，公司在做企业级 SaaS。")

    const detailCallsBefore = mocks.secureRequest.mock.calls.filter(([path]) => path === `/api/delivery/tasks/${taskId}`).length

    const cloudEvent: CloudDeliveryEvent = {
      taskId, stage: "executing", code: "EXECUTING", message: "正在执行投递", time: "2026-08-13T08:31:00Z",
    }
    await waitFor(() => {
      eventHandler({ payload: { ...cloudEvent, extraField: "额外字段不得进入操作" } })
      expect(screen.getByText(/正在执行投递/)).toBeInTheDocument()
    })

    // 明确阶段：刷新当前详情与列表
    eventHandler({ payload: { taskId, stage: "succeeded", code: "DELIVERED", message: "投递成功", time: "2026-08-13T08:32:00Z" } })
    await waitFor(() => {
      expect(mocks.secureRequest.mock.calls.filter(([path]) => path === `/api/delivery/tasks/${taskId}`).length).toBeGreaterThan(detailCallsBefore)
    })

    // 卸载后取消订阅
    unmount()
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })

  it("绑定码不进 localStorage/URL，设备撤销后刷新列表", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem")
    render(<DeliveryPage />)
    await screen.findByRole("button", { name: "Java 后端开发" })

    fireEvent.click(screen.getByRole("button", { name: /插件绑定与设备管理/ }))
    await screen.findByText("办公电脑")

    fireEvent.click(screen.getByRole("button", { name: /生成一次性绑定码/ }))
    expect(await screen.findByText("ABCD-1234")).toBeInTheDocument()
    await waitFor(() => {
      const call = findCall("POST", "/bind-code")
      expect(call).toBeTruthy()
      const key = idempotencyKeyOf(call!)
      expect(key).toBeTruthy()
      expect(key.length).toBeLessThanOrEqual(128)
    })

    // 绑定码不写入存储与 URL
    expect(setItem).not.toHaveBeenCalled()
    expect(window.localStorage.getItem("bindCode")).toBeNull()
    expect(window.location.search).not.toContain("bindCode")

    // 撤销设备需要二次确认，撤销后刷新设备列表
    const deviceCallsBefore = mocks.secureRequest.mock.calls.filter(([path]) => path === "/api/plugin/devices").length
    fireEvent.click(screen.getByRole("button", { name: "撤销" }))
    expect(screen.getByText("确认撤销该设备？")).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "确定撤销" }))

    await waitFor(() => {
      const call = findCall("POST", "/revoke")
      expect(call).toBeTruthy()
      expect(JSON.parse(String(call![1]?.body))).toEqual({ reason: "" })
    })
    await waitFor(() => {
      expect(mocks.secureRequest.mock.calls.filter(([path]) => path === "/api/plugin/devices").length).toBeGreaterThan(deviceCallsBefore)
    })
  })

  it("状态概览卡标注当前页口径，点击可筛选", async () => {
    render(<DeliveryPage />)
    await screen.findByRole("button", { name: "Java 后端开发" })

    expect(screen.getByText(/状态概览按当前筛选结果第 1 页统计/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "状态筛选：待确认" }))
    await waitFor(() => {
      const path = String(mocks.secureRequest.mock.calls.at(-1)?.[0])
      expect(path).toContain("status=PENDING_CONFIRMATION")
    })
  })
})
