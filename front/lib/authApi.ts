import { API_BASE } from "./api"

export type ApiFieldError = {
  field: string
  reason: string
}

export type ApiErrorBody = {
  code: string
  message: string
  fieldErrors?: ApiFieldError[]
  retryable?: boolean
}

export type CloudApiResponse<T> = {
  success: boolean
  data?: T
  error?: ApiErrorBody
  requestId?: string
}

export class AuthApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly fieldErrors: ApiFieldError[] = [],
    public readonly retryable = false,
  ) {
    super(message)
    this.name = "AuthApiError"
  }
}

export async function cloudApiRequest<T>(
  path: string,
  init: RequestInit = {},
  csrfToken?: string,
): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json")
  }
  if (csrfToken) {
    headers.set("X-CSRF-TOKEN", csrfToken)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers,
      credentials: "same-origin",
      cache: "no-store",
    })
  } catch {
    throw new AuthApiError(0, "NETWORK_ERROR", "无法连接认证服务，请确认 Cloud 服务已经启动", [], true)
  }

  const raw = await response.text()
  let envelope: CloudApiResponse<T> | null = null
  if (raw.trim()) {
    try {
      envelope = JSON.parse(raw) as CloudApiResponse<T>
    } catch {
      envelope = null
    }
  }

  if (!response.ok || envelope?.success === false) {
    const error = envelope?.error
    throw new AuthApiError(
      response.status,
      error?.code ?? "REQUEST_FAILED",
      error?.message ?? `请求失败（HTTP ${response.status}）`,
      error?.fieldErrors ?? [],
      error?.retryable ?? response.status >= 500,
    )
  }
  if (!envelope?.data) {
    throw new AuthApiError(response.status, "INVALID_RESPONSE", "认证服务返回了无效响应")
  }
  return envelope.data
}

export function safeNextPath(candidate: string | null | undefined): string {
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\")) {
    return "/"
  }
  return candidate
}
