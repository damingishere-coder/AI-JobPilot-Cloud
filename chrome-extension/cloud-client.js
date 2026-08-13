/**
 * GetJobsCloudClient —— Cloud API、本地凭证与执行协议共享模块。
 *
 * popup 与 background 共用同一份常量、存储键、URL 信任判断和结果映射，
 * 避免把 Token 处理、结果枚举等安全逻辑在多个大文件里重复实现。
 *
 * 安全边界（见 CLOUD_SECURITY.md）：
 * - 插件 Token 只允许进入本地存储（local only）和请求 Authorization Header，
 *   不使用同步存储，不写页面 DOM、URL、剪贴板、日志或异常文本。
 * - 只允许任务批准的本地 Cloud 开发入口（8080/8888/6866）；生产域名属于
 *   部署阶段，不在本批实现。
 * - 内容脚本结果只接受固定枚举与短固定摘要，消息文本由本地固定映射表产生。
 */
(function () {
  "use strict";

  const EXTENSION_ORIGIN = "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc";

  /** 任务批准的本地 Cloud API 入口；popup 只能从中选择，不接受任意 URL。 */
  const ALLOWED_API_BASES = Object.freeze([
    "http://localhost:8080",
    "http://127.0.0.1:8080",
    "http://localhost:8888",
    "http://127.0.0.1:8888",
    "http://localhost:6866",
    "http://127.0.0.1:6866"
  ]);

  /** 允许发送 CLOUD_DELIVERY_WAKE 的网页精确 Origin。 */
  const ALLOWED_WAKE_PAGE_ORIGINS = Object.freeze([
    "http://localhost:6866",
    "http://127.0.0.1:6866",
    "http://localhost:8080",
    "http://127.0.0.1:8080"
  ]);

  const STORAGE_KEYS = Object.freeze({
    bound: "__GET_JOBS_CLOUD_BOUND__",
    execution: "__GET_JOBS_CLOUD_EXECUTION__",
    recent: "__GET_JOBS_CLOUD_RECENT_RESULTS__"
  });

  const WAKE_MESSAGE_TYPE = "CLOUD_DELIVERY_WAKE";
  const PENDING_LIMIT = 20;

  /** 租约过期判定的小幅容差：吸收本地与服务端之间的时钟偏差。 */
  const LEASE_GRACE_MS = 60 * 1000;
  /** 最近结果数量与保留上限（只存非敏感摘要）。 */
  const RECENT_RESULTS_LIMIT = 20;
  const RECENT_RESULTS_TTL_MS = 7 * 24 * 60 * 60 * 1000;

  const API_TIMEOUT_MS = 30000;

  /** 内容脚本允许回传的失败类型（固定枚举）。 */
  const CONTENT_FAILURE_TYPES = Object.freeze([
    "LOGIN_REQUIRED",
    "CAPTCHA_REQUIRED",
    "RISK_CONTROL",
    "PAGE_CHANGED",
    "USER_ACTION_REQUIRED",
    "JOB_CLOSED",
    "BUTTON_NOT_FOUND",
    "NETWORK_ERROR",
    "UNKNOWN_ERROR"
  ]);

  const CONTENT_RESULT_CODES = Object.freeze(["DELIVERED", "ALREADY_DELIVERED"]);
  const CONTENT_PAGE_STATES = Object.freeze(["SUCCESS_NOTICE", "ALREADY_DELIVERED"]);

  /**
   * 回传消息固定映射表：message 只能从这里产生，不转发 DOM 文本、异常堆栈
   * 或内容脚本原话。文本必须单行、短、无敏感标记（服务端还有同名单行校验）。
   */
  const REPORT_MAP = Object.freeze({
    LOGIN_REQUIRED: { kind: "pause", code: "LOGIN_REQUIRED", message: "登录状态失效，请在招聘平台页面重新登录" },
    CAPTCHA_REQUIRED: { kind: "pause", code: "CAPTCHA_REQUIRED", message: "页面要求人工验证，已停止自动操作" },
    RISK_CONTROL: { kind: "pause", code: "RISK_CONTROL", message: "平台出现风控提示，已停止自动操作" },
    PAGE_CHANGED: { kind: "pause", code: "PAGE_CHANGED", message: "页面结构与预期不符，已停止自动操作" },
    USER_ACTION_REQUIRED: { kind: "pause", code: "USER_ACTION_REQUIRED", message: "需要您在页面人工确认投递结果" },
    JOB_CLOSED: { kind: "fail", code: "JOB_CLOSED", message: "岗位已关闭，无法投递" },
    BUTTON_NOT_FOUND: { kind: "fail", code: "BUTTON_NOT_FOUND", message: "未找到可用投递按钮" },
    NETWORK_ERROR: { kind: "fail", code: "NETWORK_ERROR", message: "网络或页面加载异常" },
    UNKNOWN_ERROR: { kind: "fail", code: "UNKNOWN_ERROR", message: "投递执行出现未知错误" }
  });

  /** 页面事件阶段（固定枚举）。 */
  const CLOUD_STAGES = Object.freeze([
    "accepted", "fetching", "starting", "navigating", "executing", "reporting",
    "succeeded", "failed", "paused", "offline"
  ]);

  const NON_JOB_ID_SEGMENTS = new Set(["search", "list", "home", "index", "sou", "default", "jobs"]);
  const NON_JOB_FILE_NAMES = new Set([
    "index.htm", "index.html", "home.htm", "home.html",
    "search.htm", "search.html", "default.htm", "default.html"
  ]);

  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const TOKEN_CLEAR_CODES = new Set([
    "PLUGIN_TOKEN_INVALID", "PLUGIN_TOKEN_EXPIRED", "DEVICE_REVOKED", "ACCOUNT_DISABLED"
  ]);

  // ---------------------------------------------------------------- storage

  async function readStorage(key) {
    try {
      const result = await chrome.storage.local.get(key);
      return result?.[key] ?? null;
    } catch {
      return null;
    }
  }

  async function writeStorage(key, value) {
    try {
      await chrome.storage.local.set({ [key]: value });
      return true;
    } catch {
      return false;
    }
  }

  async function removeStorage(key) {
    try {
      await chrome.storage.local.remove(key);
    } catch {
      // Storage 不可用时保持静默；Token 只在内存请求头中使用。
    }
  }

  async function readBoundState() {
    const value = await readStorage(STORAGE_KEYS.bound);
    if (!value || typeof value !== "object") return null;
    if (!ALLOWED_API_BASES.includes(value.apiBase)) return null;
    if (typeof value.token !== "string" || !value.token) return null;
    return {
      apiBase: value.apiBase,
      installationId: typeof value.installationId === "string" ? value.installationId : "",
      token: value.token,
      tokenExpiresAt: typeof value.tokenExpiresAt === "string" ? value.tokenExpiresAt : "",
      device: value.device && typeof value.device === "object" ? value.device : null
    };
  }

  async function writeBoundState(state) {
    if (!state || !ALLOWED_API_BASES.includes(state.apiBase)) return false;
    return await writeStorage(STORAGE_KEYS.bound, {
      apiBase: state.apiBase,
      installationId: String(state.installationId || ""),
      token: String(state.token || ""),
      tokenExpiresAt: String(state.tokenExpiresAt || ""),
      device: state.device && typeof state.device === "object"
        ? {
            id: String(state.device.id || ""),
            deviceName: String(state.device.deviceName || ""),
            browserName: String(state.device.browserName || ""),
            browserVersion: String(state.device.browserVersion || ""),
            extensionVersion: String(state.device.extensionVersion || ""),
            status: String(state.device.status || ""),
            capabilities: Array.isArray(state.device.capabilities) ? state.device.capabilities.map(String) : [],
            boundAt: String(state.device.boundAt || "")
          }
        : null
    });
  }

  async function clearBoundState() {
    await removeStorage(STORAGE_KEYS.bound);
  }

  async function readExecutionState() {
    const value = await readStorage(STORAGE_KEYS.execution);
    if (!value || typeof value !== "object") return null;
    if (!isUuid(value.taskId)) return null;
    return {
      taskId: value.taskId.toLowerCase(),
      executionId: typeof value.executionId === "string" ? value.executionId : "",
      startIdempotencyKey: typeof value.startIdempotencyKey === "string" ? value.startIdempotencyKey : "",
      pendingVersion: Number.isInteger(Number(value.pendingVersion)) ? Number(value.pendingVersion) : 0,
      phase: ["starting", "executing", "reporting"].includes(value.phase) ? value.phase : "starting",
      updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : "",
      leaseId: typeof value.leaseId === "string" ? value.leaseId : null,
      leaseExpiresAt: typeof value.leaseExpiresAt === "string" ? value.leaseExpiresAt : "",
      version: Number.isInteger(Number(value.version)) ? Number(value.version) : null,
      attemptNumber: Number.isInteger(Number(value.attemptNumber)) ? Number(value.attemptNumber) : null,
      task: value.task && typeof value.task === "object"
        ? {
            platform: ["BOSS", "ZHILIAN"].includes(value.task.platform) ? value.task.platform : "",
            jobUrl: typeof value.task.jobUrl === "string" ? value.task.jobUrl : "",
            greeting: typeof value.task.greeting === "string" ? value.task.greeting : ""
          }
        : null,
      report: normalizePersistedReport(value.report)
    };
  }

  /**
   * 持久化执行状态。关键字段非法/缺失时直接返回 false，绝不把空
   * executionId/幂等键、无 lease 的 executing/reporting 状态写入 storage；
   * reporting 必须携带可重放的有效报告。
   */
  async function writeExecutionState(state) {
    if (!state || !isUuid(state.taskId)) return false;
    if (!["starting", "executing", "reporting"].includes(state.phase)) return false;
    const phase = state.phase;
    const executionId = typeof state.executionId === "string" ? state.executionId.trim() : "";
    const startIdempotencyKey = typeof state.startIdempotencyKey === "string" ? state.startIdempotencyKey.trim() : "";
    if (!executionId || !startIdempotencyKey) return false;
    if ((phase === "executing" || phase === "reporting") && !String(state.leaseId || "")) return false;
    if (phase === "reporting" && !normalizePersistedReport(state.report)) return false;
    return await writeStorage(STORAGE_KEYS.execution, {
      taskId: state.taskId.toLowerCase(),
      executionId,
      startIdempotencyKey,
      pendingVersion: Number.isInteger(Number(state.pendingVersion)) ? Number(state.pendingVersion) : 0,
      phase,
      updatedAt: String(state.updatedAt || new Date().toISOString()),
      leaseId: state.leaseId == null ? null : String(state.leaseId),
      leaseExpiresAt: typeof state.leaseExpiresAt === "string" ? state.leaseExpiresAt : "",
      version: state.version == null ? null : Number(state.version),
      attemptNumber: state.attemptNumber == null ? null : Number(state.attemptNumber),
      task: state.task && typeof state.task === "object"
        ? {
            platform: ["BOSS", "ZHILIAN"].includes(state.task.platform) ? state.task.platform : "",
            jobUrl: typeof state.task.jobUrl === "string" ? state.task.jobUrl : "",
            greeting: typeof state.task.greeting === "string" ? state.task.greeting : ""
          }
        : null,
      report: state.report && typeof state.report === "object"
        ? {
            kind: ["success", "fail", "pause"].includes(state.report.kind) ? state.report.kind : "fail",
            payload: state.report.payload && typeof state.report.payload === "object" ? state.report.payload : {},
            reportIdempotencyKey: String(state.report.reportIdempotencyKey || "")
          }
        : null
    });
  }

  async function clearExecutionState() {
    await removeStorage(STORAGE_KEYS.execution);
  }

  async function readRecentResults() {
    const value = await readStorage(STORAGE_KEYS.recent);
    if (!Array.isArray(value)) return [];
    const now = Date.now();
    return value
      .filter((item) => item && typeof item === "object" && isUuid(item.taskId))
      .filter((item) => now - Date.parse(String(item.updatedAt || "")) < RECENT_RESULTS_TTL_MS)
      .slice(0, RECENT_RESULTS_LIMIT);
  }

  async function appendRecentResult(result) {
    if (!result || !isUuid(result.taskId)) return;
    const existing = await readRecentResults();
    const next = [
      {
        taskId: result.taskId.toLowerCase(),
        status: ["SUCCEEDED", "FAILED", "PAUSED"].includes(result.status) ? result.status : "FAILED",
        code: String(result.code || "").slice(0, 64),
        finishedAt: String(result.finishedAt || ""),
        updatedAt: new Date().toISOString()
      },
      ...existing.filter((item) => item.taskId !== result.taskId.toLowerCase())
    ].slice(0, RECENT_RESULTS_LIMIT);
    await writeStorage(STORAGE_KEYS.recent, next);
  }

  async function clearRecentResults() {
    await removeStorage(STORAGE_KEYS.recent);
  }

  /** 解除本机绑定：只清本机 Token 与执行状态，服务端撤销需在 Web 设备管理操作。 */
  async function unbindLocal() {
    await clearBoundState();
    await clearExecutionState();
    await clearRecentResults();
  }

  // ---------------------------------------------------------------- random

  /**
   * 安全随机：只接受 Web Crypto getRandomValues。不可用或调用失败时抛出
   * 不含内部信息的稳定错误，绝不降级为可预测随机或任何弱随机值。
   */
  function randomBytes(length) {
    const cryptoApi = globalThis.crypto;
    if (!cryptoApi || typeof cryptoApi.getRandomValues !== "function") {
      throw new Error("安全随机数生成不可用");
    }
    const bytes = new Uint8Array(length);
    try {
      cryptoApi.getRandomValues(bytes);
    } catch {
      throw new Error("安全随机数生成不可用");
    }
    return bytes;
  }

  function bytesToBase64Url(bytes) {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function bytesToHex(bytes) {
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  }

  /** 安装 ID：256-bit 随机值，base64url 编码（16-128 位、[A-Za-z0-9_-]）。 */
  function randomInstallationId() {
    return bytesToBase64Url(randomBytes(32));
  }

  /** 执行 ID：128-bit 随机值，hex 编码（32 位，符合后端 8-80 位随机串校验）。 */
  function randomExecutionId() {
    return bytesToHex(randomBytes(16));
  }

  /** 幂等键：稳定前缀 + 随机部分，长度 ≤ 128。 */
  function randomIdempotencyKey(prefix) {
    const base = String(prefix || "ajp").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 24) || "ajp";
    return `${base}_${bytesToBase64Url(randomBytes(18))}`.slice(0, 128);
  }

  // ---------------------------------------------------------------- validation

  function isAllowedApiBase(value) {
    return ALLOWED_API_BASES.includes(value);
  }

  function defaultApiBase() {
    return ALLOWED_API_BASES[0];
  }

  function allowedApiBases() {
    return ALLOWED_API_BASES.slice();
  }

  function isAllowedWakePageOrigin(origin) {
    return ALLOWED_WAKE_PAGE_ORIGINS.includes(origin);
  }

  function allowedWakePageOrigins() {
    return ALLOWED_WAKE_PAGE_ORIGINS.slice();
  }

  function isUuid(value) {
    return typeof value === "string" && UUID_PATTERN.test(value);
  }

  function isCloudWakeType(type) {
    return type === WAKE_MESSAGE_TYPE;
  }

  /** 校验网页唤醒消息：source/type、UUID taskId 与有界 requestId。 */
  function normalizeWakeMessage(message) {
    if (!message || message.source !== "GET_JOBS_PAGE" || message.type !== WAKE_MESSAGE_TYPE) {
      return { success: false, code: "VALIDATION_ERROR", message: "非法唤醒消息" };
    }
    const taskId = typeof message.taskId === "string" ? message.taskId.trim() : "";
    if (!isUuid(taskId)) {
      return { success: false, code: "VALIDATION_ERROR", message: "taskId 必须是有效 UUID" };
    }
    const requestId = typeof message.requestId === "string" ? message.requestId.trim() : "";
    if (!requestId || requestId.length > 128 || !/^[A-Za-z0-9._-]+$/.test(requestId)) {
      return { success: false, code: "VALIDATION_ERROR", message: "requestId 无效" };
    }
    return { success: true, taskId: taskId.toLowerCase(), requestId };
  }

  function currentExtensionVersion() {
    try {
      const version = chrome.runtime?.getManifest?.().version;
      if (/^[0-9]{1,9}(\.[0-9]{1,9}){0,3}$/.test(version || "")) return version;
    } catch {
      // 扩展上下文失效时由调用方兜底。
    }
    return "";
  }

  /** 扩展 Origin（由固定公钥派生的开发扩展 ID）。 */
  function extensionOrigin() {
    return EXTENSION_ORIGIN;
  }

  // ---------------------------------------------------------------- job URL trust

  function isHostOrSubdomain(host, domain) {
    return host === domain || host.endsWith(`.${domain}`);
  }

  function isPlausibleIdSegment(segment) {
    if (!segment || segment === "." || segment === "..") return false;
    if (segment.includes("/") || segment.includes("\\")) return false;
    return !NON_JOB_ID_SEGMENTS.has(segment.toLowerCase());
  }

  /**
   * 与后端 normalizeTrustedJobUrl 同一套规则：HTTPS + 正确 host label + 岗位详情
   * path；拒绝 lookalike、首页、搜索页、端口、userinfo、query/fragment、编码与
   * 点段绕过。通过时返回规范化 origin + path，否则返回空串。
   */
  function isTrustedJobUrl(url, platform) {
    if (typeof url !== "string" || !url || url.length > 2000) return "";
    let parsed;
    try {
      parsed = new URL(url);
    } catch {
      return "";
    }
    if (parsed.protocol !== "https:" || !parsed.hostname) return "";
    if (parsed.port || parsed.username || parsed.password) return "";
    if (parsed.search || parsed.hash) return "";
    const raw = String(url);
    if (raw.includes("%") || raw.includes("\\")) return "";
    // URL 解析器会规范化点段，必须在原始字符串上拒绝 /../ 与 /./。
    const rawPath = raw.split(/[?#]/)[0];
    if (/(^|\/)\.\.?(\/|$)/.test(rawPath)) return "";
    const host = parsed.hostname.toLowerCase();
    const path = parsed.pathname;
    if (!path || path.includes("//")) return "";
    const normalized = `https://${host}${path}`;

    if (platform === "BOSS") {
      if (!isHostOrSubdomain(host, "zhipin.com")) return "";
      const lower = path.toLowerCase();
      let id = null;
      if (lower.startsWith("/web/geek/job_detail/")) id = path.slice("/web/geek/job_detail/".length);
      else if (lower.startsWith("/job_detail/")) id = path.slice("/job_detail/".length);
      if (id == null || !isPlausibleIdSegment(id)) return "";
      return normalized;
    }

    if (platform === "ZHILIAN") {
      if (!isHostOrSubdomain(host, "zhaopin.com")) return "";
      const text = `${host}${path}`.toLowerCase();
      if (/company|gongsi|qiye|enterprise|firm|business|corp/.test(text)) return "";
      const lower = path.toLowerCase();
      if (/^\/sou(\/|$)|\/search\/|\/company\/|\/gongsi\/|\/qiye\//.test(lower)) return "";
      const parts = lower.split("/").filter(Boolean);
      for (let index = 0; index < parts.length - 1; index += 1) {
        if (index === parts.length - 2
            && ["jobdetail", "positiondetail", "job_detail"].includes(parts[index])
            && isPlausibleIdSegment(parts[index + 1])) {
          return normalized;
        }
      }
      if (parts.length === 2 && parts[0] === "job" && isPlausibleIdSegment(parts[1])) return normalized;
      if (isHostOrSubdomain(host, "jobs.zhaopin.com") && parts.length === 1) {
        const name = parts[0].toLowerCase();
        if ((name.endsWith(".htm") || name.endsWith(".html")) && !NON_JOB_FILE_NAMES.has(name)) return normalized;
      }
      return "";
    }

    return "";
  }

  // ---------------------------------------------------------------- cloud API

  /**
   * 统一 Cloud API 请求：只把 Token 放进请求 Header 内存；错误只回传服务端
   * envelope 的 code/message，不拼接响应体、请求头或 Token。
   */
  async function requestCloudApi(apiBase, path, options = {}) {
    const method = options.method || "GET";
    const timeoutMs = Math.max(1000, Math.min(Number(options.timeoutMs) || API_TIMEOUT_MS, 120000));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const headers = { Accept: "application/json" };
    if (options.token) headers.Authorization = `Bearer ${options.token}`;
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
    try {
      const response = await fetch(`${apiBase}${path}`, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal
      });
      const text = await response.text();
      let envelope = null;
      try {
        const parsed = text ? JSON.parse(text) : null;
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) envelope = parsed;
      } catch {
        envelope = null;
      }
      const error = envelope?.error && typeof envelope.error === "object" ? envelope.error : null;
      if (!response.ok || !envelope || envelope.success !== true) {
        return {
          success: false,
          httpStatus: response.status,
          code: typeof error?.code === "string" && error.code ? error.code : `HTTP_${response.status || 0}`,
          message: typeof error?.message === "string" && error.message ? error.message : "云端服务请求失败",
          retryable: Boolean(error?.retryable) || response.status === 429 || response.status >= 500
        };
      }
      return { success: true, httpStatus: response.status, data: envelope.data ?? null };
    } catch {
      return {
        success: false,
        httpStatus: 0,
        code: "NETWORK_ERROR",
        message: "无法连接云端服务，请确认本地服务已启动",
        retryable: true
      };
    } finally {
      clearTimeout(timer);
    }
  }

  async function bindDevice(apiBase, payload) {
    return await requestCloudApi(apiBase, "/api/plugin/bind", {
      method: "POST",
      body: payload
    });
  }

  async function fetchMe(apiBase, token) {
    return await requestCloudApi(apiBase, "/api/plugin/me", { token });
  }

  async function fetchPending(apiBase, token) {
    return await requestCloudApi(apiBase, `/api/plugin/tasks/pending?limit=${PENDING_LIMIT}`, { token });
  }

  function taskPath(taskId, action) {
    return `/api/plugin/tasks/${encodeURIComponent(taskId)}/${action}`;
  }

  async function startTask(apiBase, token, taskId, payload, idempotencyKey) {
    return await requestCloudApi(apiBase, taskPath(taskId, "start"), {
      method: "POST",
      token,
      body: payload,
      idempotencyKey
    });
  }

  async function reportSuccess(apiBase, token, taskId, payload, idempotencyKey) {
    return await requestCloudApi(apiBase, taskPath(taskId, "success"), {
      method: "POST",
      token,
      body: payload,
      idempotencyKey
    });
  }

  async function reportFail(apiBase, token, taskId, payload, idempotencyKey) {
    return await requestCloudApi(apiBase, taskPath(taskId, "fail"), {
      method: "POST",
      token,
      body: payload,
      idempotencyKey
    });
  }

  async function reportPause(apiBase, token, taskId, payload, idempotencyKey) {
    return await requestCloudApi(apiBase, taskPath(taskId, "pause"), {
      method: "POST",
      token,
      body: payload,
      idempotencyKey
    });
  }

  /** 401/403 稳定错误码出现时必须清理本机 Token。 */
  function shouldClearTokenOnCode(code) {
    return TOKEN_CLEAR_CODES.has(code);
  }

  // ---------------------------------------------------------------- report mapping

  function normalizePersistedReport(raw) {
    if (!raw || typeof raw !== "object") return null;
    if (!["success", "fail", "pause"].includes(raw.kind)) return null;
    if (!raw.payload || typeof raw.payload !== "object") return null;
    const reportIdempotencyKey = typeof raw.reportIdempotencyKey === "string"
      ? raw.reportIdempotencyKey.trim()
      : "";
    if (!reportIdempotencyKey || reportIdempotencyKey.length > 128) return null;
    return {
      kind: raw.kind,
      payload: raw.payload,
      reportIdempotencyKey
    };
  }

  function reportEntryFor(type) {
    return REPORT_MAP[type] || REPORT_MAP.UNKNOWN_ERROR;
  }

  /**
   * 把内容脚本受控结果规范为后端契约：
   * success → success 报告；failureType → pause/fail 报告；未知 → UNKNOWN_ERROR。
   * 内容脚本的 message 一律不转发，文本只来自固定映射表。
   */
  function normalizeContentResult(result, platform) {
    if (!result || typeof result !== "object") return { valid: false };
    if (result.success === true) {
      const resultCode = String(result.resultCode || "").toUpperCase();
      const pageState = String(result.pageState || "").toUpperCase();
      if (resultCode === "DELIVERED" && pageState === "SUCCESS_NOTICE") {
        return {
          valid: true,
          kind: "success",
          payload: {
            resultCode: "DELIVERED",
            evidence: { pageState: "SUCCESS_NOTICE" }
          }
        };
      }
      if (resultCode === "ALREADY_DELIVERED" && pageState === "ALREADY_DELIVERED") {
        return {
          valid: true,
          kind: "success",
          payload: {
            resultCode: "ALREADY_DELIVERED",
            evidence: { pageState: "ALREADY_DELIVERED", alreadyDelivered: true }
          }
        };
      }
      return { valid: false };
    }
    const failureType = String(result.failureType || "");
    const entry = reportEntryFor(failureType);
    if (entry.code === "UNKNOWN_ERROR" && failureType && !CONTENT_FAILURE_TYPES.includes(failureType)) {
      return { valid: false };
    }
    return { valid: true, kind: entry.kind, code: entry.code };
  }

  /**
   * 由固定映射与执行元数据构建最终报告（finish 请求体），只在发送前内存中组装。
   * retryable 恒为 false，由服务端决定可重试性。
   */
  function buildReportPayload(execution, code, nowIso) {
    const base = {
      leaseId: execution.leaseId,
      executionId: execution.executionId,
      version: execution.version
    };
    if (code === "DELIVERED" || code === "ALREADY_DELIVERED") {
      const evidence = code === "ALREADY_DELIVERED"
        ? { pageState: "ALREADY_DELIVERED", alreadyDelivered: true }
        : { pageState: "SUCCESS_NOTICE" };
      return { kind: "success", payload: { ...base, completedAt: nowIso, resultCode: code, evidence } };
    }
    const entry = reportEntryFor(code);
    if (entry.kind === "pause") {
      return { kind: "pause", payload: { ...base, pausedAt: nowIso, reason: entry.code, message: entry.message } };
    }
    return {
      kind: "fail",
      payload: { ...base, failedAt: nowIso, errorCode: entry.code, message: entry.message, retryable: false }
    };
  }

  /** 页面事件 payload 只含 taskId + 稳定 stage/code/message/time。 */
  function cloudEvent(taskId, stage, code, message) {
    return {
      taskId,
      stage: CLOUD_STAGES.includes(stage) ? stage : "offline",
      code: String(code || "").slice(0, 64),
      message: String(message || "").slice(0, 200),
      time: new Date().toISOString()
    };
  }

  /**
   * 是否仍视为活动执行（不同 task 的唤醒必须返回 PLUGIN_BUSY）：
   * - starting：start 可能已在服务端提交而响应丢失，绝不按本地时间自动清理；
   * - reporting：含不可丢的实际平台结果，绝不按本地时间自动清理；
   * - executing：以持久化 leaseExpiresAt（+容差）为准；时间字段畸形/缺失时保守 busy。
   */
  function isExecutionActive(state, now = Date.now()) {
    if (!state) return false;
    if (state.phase === "starting" || state.phase === "reporting") return true;
    if (state.phase === "executing") {
      const expiry = leaseExpiryTime(state);
      if (expiry == null) return true;
      return now < expiry + LEASE_GRACE_MS;
    }
    return false;
  }

  /** executing 且租约已明确过期（含容差）：同 task 恢复时不得再次导航/点击。 */
  function isLeaseExpired(state, now = Date.now()) {
    if (!state || state.phase !== "executing") return false;
    const expiry = leaseExpiryTime(state);
    if (expiry == null) return false;
    return now >= expiry + LEASE_GRACE_MS;
  }

  function leaseExpiryTime(state) {
    const parsed = Date.parse(String(state.leaseExpiresAt || ""));
    return Number.isFinite(parsed) ? parsed : null;
  }

  function isRetryableCloudFailure(apiResult) {
    return apiResult.httpStatus === 0 || apiResult.httpStatus === 429 || apiResult.httpStatus >= 500;
  }

  globalThis.GetJobsCloudClient = Object.freeze({
    // 常量
    EXTENSION_ORIGIN,
    ALLOWED_API_BASES,
    ALLOWED_WAKE_PAGE_ORIGINS,
    STORAGE_KEYS,
    WAKE_MESSAGE_TYPE,
    PENDING_LIMIT,
    CONTENT_FAILURE_TYPES,
    CONTENT_RESULT_CODES,
    CONTENT_PAGE_STATES,
    REPORT_MAP,
    CLOUD_STAGES,
    // 存储
    readBoundState,
    writeBoundState,
    clearBoundState,
    readExecutionState,
    writeExecutionState,
    clearExecutionState,
    readRecentResults,
    appendRecentResult,
    unbindLocal,
    // 随机
    randomInstallationId,
    randomExecutionId,
    randomIdempotencyKey,
    // 校验
    isAllowedApiBase,
    defaultApiBase,
    allowedApiBases,
    isAllowedWakePageOrigin,
    allowedWakePageOrigins,
    isUuid,
    isCloudWakeType,
    normalizeWakeMessage,
    currentExtensionVersion,
    extensionOrigin,
    isTrustedJobUrl,
    // API
    requestCloudApi,
    bindDevice,
    fetchMe,
    fetchPending,
    startTask,
    reportSuccess,
    reportFail,
    reportPause,
    shouldClearTokenOnCode,
    isRetryableCloudFailure,
    // 报告与事件
    normalizeContentResult,
    buildReportPayload,
    reportEntryFor,
    cloudEvent,
    isExecutionActive,
    isLeaseExpired
  });
})();
