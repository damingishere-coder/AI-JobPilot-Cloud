import { describe, expect, it } from "vitest"

import { safeNextPath } from "./authApi"

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
