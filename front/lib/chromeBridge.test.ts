import { afterEach, describe, expect, it, vi } from "vitest"

import {
  parseCloudDeliveryEvent,
  sendChromeBridgeMessage,
  sendCloudDeliveryWake,
  subscribeChromeBridgeEvents,
} from "@/lib/chromeBridge"

const TASK_ID = "22222222-2222-4222-8222-222222222222"

function setWindowOrigin(origin: string) {
  Object.defineProperty(window, "location", {
    value: { ...window.location, origin },
    writable: true,
    configurable: true,
  })
}

function emitBridgeMessage(origin: string, data: unknown) {
  const event = new MessageEvent("message", { data, origin })
  Object.defineProperty(event, "source", { value: window, configurable: true })
  window.dispatchEvent(event)
}

function bridgeResponseEnvelope(requestId: string, response: unknown) {
  return {
    source: "GET_JOBS_EXTENSION",
    requestId,
    type: "CLOUD_DELIVERY_WAKE_RESPONSE",
    response,
  }
}

function captureOutgoingPostMessage() {
  const posted: unknown[] = []
  const spy = vi.spyOn(window, "postMessage").mockImplementation((message: unknown) => {
    posted.push(message)
  })
  return { posted, spy }
}

/** 自动以指定响应回复下一条 postMessage 发出的 Cloud wake 请求。 */
function autoReplyWake(origin: string, response: unknown) {
  const replies = captureOutgoingPostMessage()
  const originalPost = replies.spy.getMockImplementation()
  replies.spy.mockImplementation((message: unknown) => {
    const payload = message as Record<string, unknown>
    const requestId = typeof payload.requestId === "string" ? payload.requestId : ""
    if (requestId) {
      queueMicrotask(() => emitBridgeMessage(origin, bridgeResponseEnvelope(requestId, response)))
    }
    originalPost?.(message)
  })
  return replies
}

afterEach(() => {
  vi.restoreAllMocks()
  // 恢复默认来源，避免影响同文件其他用例
  Object.defineProperty(window, "location", {
    value: { ...window.location, origin: "http://localhost:3000" },
    writable: true,
    configurable: true,
  })
})

describe("chromeBridge Cloud 白名单与来源校验", () => {
  it("sendCloudDeliveryWake 拒绝非法 UUID，且不发送任何消息", async () => {
    setWindowOrigin("http://localhost:6866")
    const { posted } = captureOutgoingPostMessage()

    const result = await sendCloudDeliveryWake("not-a-uuid")

    expect(result.accepted).toBe(false)
    expect(result.code).toBe("VALIDATION_ERROR")
    expect(posted).toHaveLength(0)
  })

  it("sendCloudDeliveryWake 在非白名单来源直接拒绝", async () => {
    setWindowOrigin("http://localhost:3000")
    const { posted } = captureOutgoingPostMessage()

    const result = await sendCloudDeliveryWake(TASK_ID)

    expect(result.accepted).toBe(false)
    expect(result.code).toBe("ORIGIN_NOT_ALLOWED")
    expect(posted).toHaveLength(0)
  })

  it("合法 wake 在 6866 只发送 source/type/taskId/requestId 必要字段", async () => {
    setWindowOrigin("http://localhost:6866")
    autoReplyWake("http://localhost:6866", {
      success: true,
      accepted: true,
      taskId: TASK_ID,
      state: "accepted",
      code: "ACCEPTED",
      message: "已接收投递唤醒请求",
    })

    const result = await sendCloudDeliveryWake(TASK_ID)

    expect(result.accepted).toBe(true)
    const posted = vi.mocked(window.postMessage).mock.calls[0]?.[0] as Record<string, unknown>
    expect(Object.keys(posted).sort()).toEqual(["requestId", "source", "taskId", "type"])
    expect(posted.source).toBe("GET_JOBS_PAGE")
    expect(posted.type).toBe("CLOUD_DELIVERY_WAKE")
    expect(posted.taskId).toBe(TASK_ID)
    expect(String(posted.requestId)).toMatch(/^[A-Za-z0-9._-]+$/)
  })

  it("合法 wake 在 8080 同样工作", async () => {
    setWindowOrigin("http://127.0.0.1:8080")
    autoReplyWake("http://127.0.0.1:8080", {
      success: true,
      accepted: true,
      taskId: TASK_ID,
      state: "resuming",
      code: "ACCEPTED",
      message: "该任务正在执行，已恢复等待",
      rawMessage: "不应透出的内部运行时错误",
    })

    const result = await sendCloudDeliveryWake(TASK_ID)

    expect(result.accepted).toBe(true)
    expect(result.state).toBe("resuming")
    // rawMessage 等未知字段不得进入结果
    expect(result).not.toHaveProperty("rawMessage")
    expect(Object.keys(result).sort()).toEqual(["accepted", "code", "message", "state", "success", "taskId"])
  })

  it("伪造响应（taskId 被替换或缺少 accepted）一律视为未接收", async () => {
    setWindowOrigin("http://localhost:6866")
    autoReplyWake("http://localhost:6866", {
      success: true,
      accepted: true,
      taskId: "99999999-9999-4999-8999-999999999999",
      code: "ACCEPTED",
    })

    const forgedTask = await sendCloudDeliveryWake(TASK_ID)
    expect(forgedTask.accepted).toBe(false)

    autoReplyWake("http://localhost:6866", { success: true, taskId: TASK_ID, code: "ACCEPTED" })
    const missingAccepted = await sendCloudDeliveryWake(TASK_ID)
    expect(missingAccepted.accepted).toBe(false)
    expect(missingAccepted.code).toBe("EXTENSION_UNAVAILABLE")
  })

  it("成功响应缺少 taskId 时也视为未接收", async () => {
    setWindowOrigin("http://localhost:6866")
    autoReplyWake("http://localhost:6866", {
      success: true,
      accepted: true,
      code: "ACCEPTED",
      message: "缺少任务回显",
    })

    const result = await sendCloudDeliveryWake(TASK_ID)

    expect(result.accepted).toBe(false)
    expect(result.code).toBe("EXTENSION_UNAVAILABLE")
  })

  it("扩展显式拒绝（未绑定/忙碌）时透出稳定原因", async () => {
    setWindowOrigin("http://localhost:6866")
    autoReplyWake("http://localhost:6866", {
      success: false,
      accepted: false,
      taskId: TASK_ID,
      state: "unbound",
      code: "PLUGIN_NOT_BOUND",
      message: "插件尚未绑定云端设备，请点击扩展图标完成绑定",
    })

    const result = await sendCloudDeliveryWake(TASK_ID)

    expect(result.accepted).toBe(false)
    expect(result.code).toBe("PLUGIN_NOT_BOUND")
    expect(result.message).toContain("插件尚未绑定云端设备")
  })

  it("扩展超时无响应时返回稳定的未接收结果", async () => {
    setWindowOrigin("http://localhost:6866")
    captureOutgoingPostMessage()

    const result = await sendCloudDeliveryWake(TASK_ID, 20)

    expect(result.accepted).toBe(false)
    expect(result.success).toBe(false)
    expect(result.code).toBe("EXTENSION_UNAVAILABLE")
    expect(result.message).toBeTruthy()
  })

  it("旧 Bridge 调用（ping/通用消息）保持兼容", async () => {
    setWindowOrigin("http://localhost:6866")
    autoReplyWake("http://localhost:6866", { success: true, message: "Chrome扩展已连接", version: "1.0.0" })

    await expect(sendChromeBridgeMessage({ type: "GET_JOBS_EXTENSION_PING" }, 100)).resolves.toMatchObject({ success: true })
  })
})

describe("chromeBridge Cloud 投递事件订阅", () => {
  it("事件订阅验证 source/type/origin，Cloud 事件通过安全守卫", async () => {
    setWindowOrigin("http://localhost:6866")
    const events: unknown[] = []
    const unsubscribe = subscribeChromeBridgeEvents((event) => events.push(event))

    emitBridgeMessage("http://localhost:6866", {
      source: "GET_JOBS_EXTENSION",
      type: "GET_JOBS_EXTENSION_EVENT",
      version: "1.0.0",
      payload: {
        taskId: TASK_ID,
        stage: "succeeded",
        code: "DELIVERED",
        message: "投递成功",
        time: "2026-08-13T08:00:00Z",
        extraField: "必须被丢弃",
      },
    })
    // 非扩展来源与错误 origin 的事件必须被忽略
    emitBridgeMessage("http://localhost:6866", {
      source: "GET_JOBS_PAGE",
      type: "GET_JOBS_EXTENSION_EVENT",
      payload: { taskId: TASK_ID, stage: "failed", code: "X", message: "伪造" },
    })
    emitBridgeMessage("http://localhost:9999", {
      source: "GET_JOBS_EXTENSION",
      type: "GET_JOBS_EXTENSION_EVENT",
      payload: { taskId: TASK_ID, stage: "failed", code: "X", message: "伪造" },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(events).toHaveLength(1)
    const parsed = parseCloudDeliveryEvent((events[0] as { payload?: unknown }).payload)
    expect(parsed).toEqual({
      taskId: TASK_ID,
      stage: "succeeded",
      code: "DELIVERED",
      message: "投递成功",
      time: "2026-08-13T08:00:00Z",
    })
    unsubscribe()
  })

  it("卸载后取消监听，不再接收事件", async () => {
    setWindowOrigin("http://localhost:6866")
    const events: unknown[] = []
    const unsubscribe = subscribeChromeBridgeEvents((event) => events.push(event))
    unsubscribe()

    emitBridgeMessage("http://localhost:6866", {
      source: "GET_JOBS_EXTENSION",
      type: "GET_JOBS_EXTENSION_EVENT",
      payload: { taskId: TASK_ID, stage: "accepted", code: "ACCEPTED", message: "已接收" },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(events).toHaveLength(0)
  })

  it("parseCloudDeliveryEvent 拒绝非法 taskId、未知 stage 与旧页面事件负载", () => {
    expect(parseCloudDeliveryEvent({ taskId: "not-a-uuid", stage: "failed", code: "X", message: "x" })).toBeNull()
    expect(parseCloudDeliveryEvent({ taskId: TASK_ID, stage: "malicious-stage", code: "X", message: "x" })).toBeNull()
    expect(parseCloudDeliveryEvent({ platform: "boss", type: "scan", message: "旧事件" })).toBeNull()
    expect(parseCloudDeliveryEvent(null)).toBeNull()
    expect(parseCloudDeliveryEvent("not-an-object")).toBeNull()
  })
})
