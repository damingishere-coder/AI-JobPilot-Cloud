export type ChromeBridgeResponse<T = unknown> = {
  success: boolean
  message?: string
  version?: string
  rawMessage?: string
  data?: T
  [key: string]: unknown
}

const SOURCE = 'GET_JOBS_PAGE'
const TARGET = 'GET_JOBS_EXTENSION'
const CLOUD_WAKE_TYPE = 'CLOUD_DELIVERY_WAKE'
const CLOUD_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
// Cloud Web 开发源精确白名单：localhost/127.0.0.1 的 6866 与 8080。
// 不使用 *、任意端口或任意域名。
const ALLOWED_BRIDGE_ORIGINS = new Set([
  'http://localhost:6866',
  'http://127.0.0.1:6866',
  'http://localhost:8080',
  'http://127.0.0.1:8080',
])

// 扩展 Cloud 投递事件使用与 cloud-client.js CLOUD_STAGES 一致的白名单。
const CLOUD_EVENT_STAGES = new Set([
  'accepted', 'fetching', 'starting', 'navigating', 'executing', 'reporting',
  'succeeded', 'failed', 'paused', 'offline',
])

export type ChromeBridgeEvent = {
  type?: string
  payload?: {
    platform?: string
    type?: string
    message?: string
    timestamp?: number
    [key: string]: unknown
  }
  version?: string
  [key: string]: unknown
}

type ChromeBridgeMessageEnvelope<T = unknown> = {
  source?: string
  requestId?: string
  type?: string
  response?: ChromeBridgeResponse<T>
}

export function sendChromeBridgeMessage<T = unknown>(payload: Record<string, unknown>, timeout = 30000): Promise<ChromeBridgeResponse<T>> {
  if (typeof window === 'undefined') {
    return Promise.resolve({ success: false, message: '当前环境不支持Chrome扩展通信。' })
  }

  const targetOrigin = getBridgeTargetOrigin()
  if (!targetOrigin) {
    return Promise.resolve({ success: false, message: '当前页面来源不允许连接 Chrome Bridge。' })
  }

  const requestId = createBridgeRequestId()
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => {
      window.removeEventListener('message', onMessage)
      resolve({ success: false, message: 'Chrome扩展未响应，请确认已加载 投递牛马 Cloud Bridge。' })
    }, timeout)

    const onMessage = (event: MessageEvent) => {
      if (event.source !== window) return
      if (event.origin !== targetOrigin) return
      const data = event.data as ChromeBridgeMessageEnvelope<T>
      if (!data || data.source !== TARGET || data.requestId !== requestId) return
      window.clearTimeout(timer)
      window.removeEventListener('message', onMessage)
      resolve(data.response || { success: false, message: 'Chrome扩展返回为空。' })
    }

    window.addEventListener('message', onMessage)
    window.postMessage({ ...payload, source: SOURCE, requestId }, targetOrigin)
  })
}

export async function pingChromeBridge(): Promise<boolean> {
  const res = await sendChromeBridgeMessage({ type: 'GET_JOBS_EXTENSION_PING' }, 1500)
  return !!res.success
}

export async function getChromeBridgeStatus(): Promise<ChromeBridgeResponse> {
  return sendChromeBridgeMessage({ type: 'GET_JOBS_EXTENSION_PING' }, 1500)
}

export function subscribeChromeBridgeEvents(handler: (event: ChromeBridgeEvent) => void): () => void {
  if (typeof window === 'undefined') return () => {}
  const targetOrigin = getBridgeTargetOrigin()
  if (!targetOrigin) return () => {}

  const onMessage = (event: MessageEvent) => {
    if (event.source !== window) return
    if (event.origin !== targetOrigin) return
    const data = event.data as ChromeBridgeEvent & { source?: string }
    if (!data || data.source !== TARGET || data.type !== 'GET_JOBS_EXTENSION_EVENT') return
    handler(data)
  }

  window.addEventListener('message', onMessage)
  return () => window.removeEventListener('message', onMessage)
}

// ---- Cloud 投递唤醒 ----

export type CloudWakeResult = {
  success: boolean
  accepted: boolean
  taskId: string
  state?: string
  code: string
  message: string
}

export type CloudDeliveryEvent = {
  taskId: string
  stage: string
  code: string
  message: string
  time?: string
}

/**
 * Cloud 投递唤醒：先严格校验 UUID，只发送 source/type/taskId/requestId
 * 对应的必要负载。结果只消费稳定 success/accepted/taskId/state/code/message
 * 字段，不展示 rawMessage 或未知运行时错误。
 */
export async function sendCloudDeliveryWake(taskId: string, timeout = 15000): Promise<CloudWakeResult> {
  if (typeof window === 'undefined') {
    return { success: false, accepted: false, taskId, code: 'BRIDGE_UNAVAILABLE', message: '当前环境不支持Chrome扩展通信。' }
  }
  const normalized = taskId.trim()
  if (!CLOUD_UUID_PATTERN.test(normalized)) {
    return { success: false, accepted: false, taskId: normalized, code: 'VALIDATION_ERROR', message: 'taskId 必须是有效 UUID' }
  }
  if (!getBridgeTargetOrigin()) {
    return { success: false, accepted: false, taskId: normalized, code: 'ORIGIN_NOT_ALLOWED', message: '当前页面来源不允许连接 Chrome Bridge。' }
  }
  const response = await sendChromeBridgeMessage({ type: CLOUD_WAKE_TYPE, taskId: normalized }, timeout)
  return normalizeCloudWakeResult(normalized, response)
}

function normalizeCloudWakeResult(requestedTaskId: string, response: ChromeBridgeResponse): CloudWakeResult {
  const echoedTaskId = typeof response.taskId === 'string' ? response.taskId.trim() : ''
  const echoMatches = echoedTaskId.toLowerCase() === requestedTaskId.toLowerCase()
  const taskId = echoMatches ? echoedTaskId : requestedTaskId
  // 伪造/不一致响应（taskId 被替换、accepted 缺失）一律视为未接收，
  // 不透出其中的 code/message；扩展显式 accepted:false 时才透出稳定原因。
  if (echoMatches && response.success === true && response.accepted === true) {
    const state = typeof response.state === 'string' && response.state ? response.state.slice(0, 32) : undefined
    const code = typeof response.code === 'string' && response.code ? response.code.slice(0, 64) : 'ACCEPTED'
    const message = typeof response.message === 'string' && response.message ? response.message.slice(0, 200) : '插件已接收投递唤醒请求'
    return { success: true, accepted: true, taskId, ...(state ? { state } : {}), code, message }
  }
  if (echoMatches && response.accepted === false) {
    const code = typeof response.code === 'string' && response.code ? response.code.slice(0, 64) : 'EXTENSION_UNAVAILABLE'
    const message = typeof response.message === 'string' && response.message ? response.message.slice(0, 200) : '插件未接收投递唤醒请求'
    return { success: false, accepted: false, taskId, code, message }
  }
  return { success: false, accepted: false, taskId, code: 'EXTENSION_UNAVAILABLE', message: '插件未响应投递唤醒请求' }
}

/**
 * Cloud 投递事件安全类型守卫：只接受合法 UUID taskId 与白名单 stage，
 * 额外字段一律丢弃，不进入任务操作。旧页面事件不受影响。
 */
export function parseCloudDeliveryEvent(payload: unknown): CloudDeliveryEvent | null {
  if (!payload || typeof payload !== 'object') return null
  const candidate = payload as Record<string, unknown>
  const taskId = typeof candidate.taskId === 'string' ? candidate.taskId.trim() : ''
  if (!CLOUD_UUID_PATTERN.test(taskId)) return null
  const stage = typeof candidate.stage === 'string' ? candidate.stage : ''
  if (!CLOUD_EVENT_STAGES.has(stage)) return null
  const code = typeof candidate.code === 'string' ? candidate.code.slice(0, 64) : ''
  const message = typeof candidate.message === 'string' ? candidate.message.slice(0, 200) : ''
  const time = typeof candidate.time === 'string' ? candidate.time.slice(0, 40) : undefined
  return { taskId, stage, code, message, ...(time ? { time } : {}) }
}

function getBridgeTargetOrigin(): string {
  if (typeof window === 'undefined') return ''
  const origin = window.location.origin
  return ALLOWED_BRIDGE_ORIGINS.has(origin) ? origin : ''
}

function createBridgeRequestId(): string {
  const browserCrypto = globalThis.crypto
  if (browserCrypto && typeof browserCrypto.randomUUID === 'function') {
    return browserCrypto.randomUUID()
  }
  if (browserCrypto && typeof browserCrypto.getRandomValues === 'function') {
    const bytes = browserCrypto.getRandomValues(new Uint8Array(16))
    return `bridge-${Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')}`
  }
  throw new Error('当前浏览器无法生成安全通信标识，请升级浏览器后重试')
}
