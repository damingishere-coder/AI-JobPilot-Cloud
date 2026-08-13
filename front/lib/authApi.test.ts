import { afterEach, describe, expect, it, vi } from "vitest"

import { cloudApiRequest, safeNextPath } from "./authApi"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("cloudApiRequest", () => {
  it("上传 FormData 时不手动设置 Content-Type，交给浏览器补充 boundary", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      data: { accepted: true },
    }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)
    const body = new FormData()
    body.append("file", new File(["resume"], "resume.txt", { type: "text/plain" }))

    await cloudApiRequest("/api/resumes/upload", { method: "POST", body }, "csrf-token")

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    const headers = init.headers as Headers
    expect(headers.has("Content-Type")).toBe(false)
    expect(headers.get("X-CSRF-TOKEN")).toBe("csrf-token")
    expect(init.body).toBe(body)
  })

  it("接受业务上合法的 null data", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      data: null,
    }), { status: 200 })))

    await expect(cloudApiRequest<null>("/api/resumes/current")).resolves.toBeNull()
  })
})

describe("safeNextPath", () => {
  it("保留站内绝对路径及查询参数", () => {
    expect(safeNextPath("/boss?tab=pending")).toBe("/boss?tab=pending")
  })

  it.each([null, "", "boss", "//evil.example", "/\\evil.example"])(
    "拒绝外部或格式不安全的跳转地址 %s",
    (candidate) => {
      expect(safeNextPath(candidate)).toBe("/")
    },
  )
})
