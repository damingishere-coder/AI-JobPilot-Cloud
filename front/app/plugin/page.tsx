"use client"

import { useCallback, useEffect, useState } from "react"
import {
  BiCopy,
  BiExtension,
  BiHide,
  BiLoaderAlt,
  BiRefresh,
  BiShieldX,
  BiShow,
  BiXCircle,
} from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { AuthApiError } from "@/lib/authApi"
import { formatDateTime, newIdempotencyKey } from "@/lib/cloudTypes"

type BindCode = {
  bindCode: string
  expiresAt: string
  expiresInSeconds: number
}

type PluginDevice = {
  id: string
  deviceName: string
  browserName: string | null
  browserVersion: string | null
  extensionVersion: string
  status: string
  capabilities: string[]
  lastSeenAt: string | null
  boundAt: string
  revokedAt: string | null
  revokeReason: string | null
}

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "正常",
  REVOKED: "已撤销",
  DISABLED: "已禁用",
}

const STATUS_TONES: Record<string, string> = {
  ACTIVE: "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300",
  REVOKED: "bg-rose-50 text-rose-600 dark:bg-rose-500/15 dark:text-rose-300",
  DISABLED: "bg-slate-100 text-slate-500 dark:bg-white/10 dark:text-waterloo",
}

export default function PluginPage() {
  const { secureRequest } = useAuth()
  const [devices, setDevices] = useState<PluginDevice[]>([])
  const [devicesLoading, setDevicesLoading] = useState(true)
  const [devicesError, setDevicesError] = useState("")
  const [bindCode, setBindCode] = useState<BindCode | null>(null)
  const [codeVisible, setCodeVisible] = useState(false)
  const [codeCreating, setCodeCreating] = useState(false)
  const [codeError, setCodeError] = useState("")
  const [notice, setNotice] = useState("")
  const [revokingId, setRevokingId] = useState<string | null>(null)
  const [remainingSeconds, setRemainingSeconds] = useState(0)

  const loadDevices = useCallback(async () => {
    setDevicesLoading(true)
    setDevicesError("")
    try {
      const data = await secureRequest<PluginDevice[]>("/api/plugin/devices")
      setDevices(data)
    } catch (error) {
      setDevicesError(error instanceof AuthApiError ? error.message : "无法加载设备列表")
    } finally {
      setDevicesLoading(false)
    }
  }, [secureRequest])

  useEffect(() => {
    void loadDevices()
  }, [loadDevices])

  // 绑定码倒计时：过期自动清空展示。
  useEffect(() => {
    if (!bindCode) return
    const tick = () => {
      const remaining = Math.max(0, Math.floor((new Date(bindCode.expiresAt).getTime() - Date.now()) / 1000))
      setRemainingSeconds(remaining)
      if (remaining <= 0) {
        setBindCode(null)
        setCodeVisible(false)
      }
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [bindCode])

  async function handleCreateBindCode() {
    setCodeCreating(true)
    setCodeError("")
    setNotice("")
    try {
      const data = await secureRequest<BindCode>("/api/plugin/bind-code", {
        method: "POST",
        headers: { "Idempotency-Key": newIdempotencyKey() },
      })
      setBindCode(data)
      setCodeVisible(false)
    } catch (error) {
      setCodeError(error instanceof AuthApiError ? error.message : "绑定码生成失败，请稍后再试")
    } finally {
      setCodeCreating(false)
    }
  }

  async function copyCode() {
    if (!bindCode) return
    try {
      await navigator.clipboard.writeText(bindCode.bindCode)
      setNotice("绑定码已复制到剪贴板")
    } catch {
      setNotice("复制失败，请手动选中复制")
    }
  }

  async function handleRevoke(device: PluginDevice) {
    if (device.status !== "ACTIVE") return
    const reason = window.prompt(
      `确认撤销设备「${device.deviceName}」？撤销后该设备立即失去云端权限。可填写撤销原因：`,
    )
    if (reason === null) return
    setRevokingId(device.id)
    setNotice("")
    try {
      await secureRequest(`/api/plugin/devices/${device.id}/revoke`, {
        method: "POST",
        body: JSON.stringify({ reason: reason.slice(0, 255) || null }),
      })
      setNotice(`设备「${device.deviceName}」已撤销`)
      await loadDevices()
    } catch (error) {
      setNotice(error instanceof AuthApiError ? error.message : "撤销失败，请稍后再试")
    } finally {
      setRevokingId(null)
    }
  }

  return (
    <div>
      <PageHeader
        icon={<BiExtension />}
        title="浏览器插件"
        subtitle="生成一次性绑定码，把 Chrome 扩展绑定到你的云端账号；可随时查看并撤销已绑定设备。"
        iconClass="text-blue-600 dark:text-blue-300"
        accentBgClass="bg-blue-500/10 dark:bg-blue-500/20"
        actions={
          <Button variant="outline" size="sm" onClick={() => void loadDevices()} disabled={devicesLoading}>
            {devicesLoading ? <BiLoaderAlt className="animate-spin" /> : <BiRefresh />}
            刷新设备
          </Button>
        }
      />

      <div className="grid gap-5 lg:grid-cols-2">
        {/* 绑定码 */}
        <Card>
          <CardHeader>
            <CardTitle>生成绑定码</CardTitle>
            <CardDescription>
              在 Chrome 扩展弹窗中输入绑定码完成配对。绑定码一次性有效，默认 5 分钟后过期，数据库只保存不可逆的哈希。
            </CardDescription>
          </CardHeader>
          <CardContent>
            {bindCode ? (
              <div className="space-y-3">
                <div className="flex items-center gap-3 rounded-lg border border-blue-200 bg-blue-50/60 px-4 py-3 dark:border-blue-500/30 dark:bg-blue-500/10">
                  <code className="flex-1 select-all font-mono text-2xl font-bold tracking-[0.2em] text-blue-700 dark:text-blue-300">
                    {codeVisible ? bindCode.bindCode : "•".repeat(bindCode.bindCode.length)}
                  </code>
                  <button
                    type="button"
                    className="rounded-lg p-2 text-blue-600 transition hover:bg-blue-100 dark:text-blue-300 dark:hover:bg-blue-500/20"
                    onClick={() => setCodeVisible((visible) => !visible)}
                    title={codeVisible ? "隐藏绑定码" : "显示绑定码"}
                    aria-label={codeVisible ? "隐藏绑定码" : "显示绑定码"}
                  >
                    {codeVisible ? <BiHide /> : <BiShow />}
                  </button>
                  <button
                    type="button"
                    className="rounded-lg p-2 text-blue-600 transition hover:bg-blue-100 dark:text-blue-300 dark:hover:bg-blue-500/20"
                    onClick={() => void copyCode()}
                    title="复制绑定码"
                    aria-label="复制绑定码"
                  >
                    <BiCopy />
                  </button>
                </div>
                <p className="text-sm text-slate-500 dark:text-manatee">
                  剩余有效时间{" "}
                  <span className={remainingSeconds <= 60 ? "font-semibold text-rose-600" : "font-semibold"}>
                    {Math.floor(remainingSeconds / 60)}:{String(remainingSeconds % 60).padStart(2, "0")}
                  </span>
                  ，请在扩展弹窗中尽快使用。使用或过期后请重新生成。
                </p>
              </div>
            ) : (
              <div className="flex flex-col items-start gap-3">
                <p className="text-sm text-slate-500 dark:text-manatee">
                  点击生成后，把绑定码输入 Chrome 扩展「投递牛马 Cloud Bridge」的弹窗即可完成绑定。
                </p>
                <Button onClick={() => void handleCreateBindCode()} disabled={codeCreating}>
                  {codeCreating ? <BiLoaderAlt className="animate-spin" /> : <BiExtension />}
                  生成一次性绑定码
                </Button>
              </div>
            )}
            {codeError && <p className="mt-3 text-sm text-rose-600 dark:text-rose-400">{codeError}</p>}
            {notice && <p className="mt-3 text-sm text-emerald-600 dark:text-emerald-400">{notice}</p>}
          </CardContent>
        </Card>

        {/* 设备列表 */}
        <Card>
          <CardHeader>
            <CardTitle>已绑定设备</CardTitle>
            <CardDescription>每台设备持有独立令牌，撤销后立即失效。绑定设备数量上限内可随时重新绑定。</CardDescription>
          </CardHeader>
          <CardContent>
            {devicesLoading ? (
              <div className="flex items-center justify-center py-10 text-slate-400">
                <BiLoaderAlt className="animate-spin text-2xl" />
              </div>
            ) : devicesError ? (
              <p className="py-6 text-sm text-rose-600 dark:text-rose-400">{devicesError}</p>
            ) : devices.length === 0 ? (
              <p className="py-6 text-sm text-slate-500 dark:text-manatee">
                还没有绑定任何设备。先生成绑定码，然后在 Chrome 扩展中完成绑定。
              </p>
            ) : (
              <ul className="space-y-3">
                {devices.map((device) => (
                  <li
                    key={device.id}
                    className="rounded-lg border border-slate-200/80 p-4 dark:border-white/10"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="truncate font-semibold text-slate-800 dark:text-slate-100">
                            {device.deviceName}
                          </p>
                          <span className={`shrink-0 rounded-md px-2 py-0.5 text-xs font-medium ${STATUS_TONES[device.status] ?? STATUS_TONES.DISABLED}`}>
                            {STATUS_LABELS[device.status] ?? device.status}
                          </span>
                        </div>
                        <p className="mt-1 truncate text-xs text-slate-500 dark:text-manatee">
                          {[device.browserName, device.browserVersion].filter(Boolean).join(" ") || "未知浏览器"}
                          {" · "}扩展 v{device.extensionVersion}
                          {" · "}能力：{device.capabilities.join("、") || "无"}
                        </p>
                        <p className="mt-1 text-xs text-slate-400 dark:text-waterloo">
                          绑定于 {formatDateTime(device.boundAt)} · 最近活跃 {formatDateTime(device.lastSeenAt)}
                          {device.revokedAt ? ` · 撤销于 ${formatDateTime(device.revokedAt)}` : ""}
                          {device.revokeReason ? ` · 原因：${device.revokeReason}` : ""}
                        </p>
                      </div>
                      {device.status === "ACTIVE" && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="shrink-0 border-rose-200 text-rose-600 hover:bg-rose-50 dark:border-rose-500/30 dark:text-rose-400"
                          disabled={revokingId === device.id}
                          onClick={() => void handleRevoke(device)}
                        >
                          {revokingId === device.id ? <BiLoaderAlt className="animate-spin" /> : <BiShieldX />}
                          撤销
                        </Button>
                      )}
                      {device.status !== "ACTIVE" && <BiXCircle className="shrink-0 text-slate-300 dark:text-white/20" />}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
