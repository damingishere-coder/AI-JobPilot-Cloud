/**
 * GetJobsCloudClient —— Cloud API、本地凭证与执行协议共享模块。
 *
 * popup 与 background 共用同一份常量、存储键、URL 信任判断和结果映射，
 * 避免把 Token 处理、结果枚举等安全逻辑在多个大文件里重复实现。
 *
 * 安全边界（见 CLOUD_SECURITY.md）：
 * - 插件 Token 只允许进入本地存储（local only）和请求 Authorization Header，
 *   不使用同步存储，不写页面 DOM、URL、剪贴板、日志或异常文本。
 * - Cloud API 地址可配置：本地仅 http://localhost|127.0.0.1 + 明确端口，
 *   远程仅 https:// 合法 Origin（拒绝 userinfo/path/query/fragment）；
 *   远程 origin 在绑定时通过 chrome.permissions 按需请求精确 `${origin}/*`，
 *   用户拒绝则 fail-closed（不保存 Token）。每次 API 调用前都重新校验地址。
 * - 内容脚本结果只接受固定枚举与短固定摘要，消息文本由本地固定映射表产生。
 */
(function () {
  "use strict";

  const EXTENSION_ORIGIN = "chrome-extension://ompipmnadogogfbebnmjgbbcadildpbc";

  /** 预设的 Cloud API 入口；popup 可从列表选择或输入自定义地址。 */
  const APPROVED_API_BASE_PRESETS = Object.freeze([
    "https://toudiniuma.cn",
    "http://localhost:8080",
    "http://127.0.0.1:8080",
    "http://localhost:8888",
    "http://127.0.0.1:8888",
    "http://localhost:6866",
    "http://127.0.0.1:6866"
  ]);

  /** popup 地址下拉中“自定义地址…”选项的值。 */
  const CUSTOM_API_BASE_VALUE = "__custom__";

  /** 允许发送 CLOUD_DELIVERY_WAKE 的网页精确 Origin。 */
  const ALLOWED_WAKE_PAGE_ORIGINS = Object.freeze([
    "https://toudiniuma.cn",
    "http://localhost:6866",
    "http://127.0.0.1:6866",
    "http://localhost:8080",
    "http://127.0.0.1:8080"
  ]);

  const STORAGE_KEYS = Object.freeze({
    bound: "__GET_JOBS_CLOUD_BOUND__",
    execution: "__GET_JOBS_CLOUD_EXECUTION__",
    recent: "__GET_JOBS_CLOUD_RECENT_RESULTS__",
    captureQueue: "__GET_JOBS_CLOUD_CAPTURE_QUEUE__",
    pauseState: "__GET_JOBS_CLOUD_PAUSE_STATE__"
  });

  const WAKE_MESSAGE_TYPE = "CLOUD_DELIVERY_WAKE";
  const PENDING_LIMIT = 20;

  /** 租约过期判定的小幅容差：吸收本地与服务端之间的时钟偏差。 */
  const LEASE_GRACE_MS = 60 * 1000;
  /** 最近结果数量与保留上限（只存非敏感摘要）。 */
  const RECENT_RESULTS_LIMIT = 20;
  const RECENT_RESULTS_TTL_MS = 7 * 24 * 60 * 60 * 1000;
  /** 连续失败阈值：达到后扩展调用 batch-pause 并停止本轮，成功重置计数。 */
  const FAILURE_THRESHOLD = 3;

  const API_TIMEOUT_MS = 30000;

  /**
   * 内容脚本允许回传的失败类型（P8 统一固定枚举）：
   * LOGIN_REQUIRED / CAPTCHA_REQUIRED / RISK_CONTROL 会 pause；
   * JOB_EXPIRED / BUTTON_NOT_FOUND / PAGE_STRUCTURE_CHANGED 会 fail；
   * NETWORK_ERROR / UNKNOWN_ERROR 可重试。
   */
  const CONTENT_FAILURE_TYPES = Object.freeze([
    "LOGIN_REQUIRED",
    "CAPTCHA_REQUIRED",
    "RISK_CONTROL",
    "JOB_EXPIRED",
    "BUTTON_NOT_FOUND",
    "PAGE_STRUCTURE_CHANGED",
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
    JOB_EXPIRED: { kind: "fail", code: "JOB_EXPIRED", message: "岗位已关闭或过期，无法投递" },
    BUTTON_NOT_FOUND: { kind: "fail", code: "BUTTON_NOT_FOUND", message: "未找到可用投递按钮" },
    PAGE_STRUCTURE_CHANGED: { kind: "fail", code: "PAGE_STRUCTURE_CHANGED", message: "页面结构与预期不符，无法确认投递" },
    NETWORK_ERROR: { kind: "fail", code: "NETWORK_ERROR", message: "网络或页面加载异常" },
    UNKNOWN_ERROR: { kind: "fail", code: "UNKNOWN_ERROR", message: "投递执行出现未知错误" }
  });

  /** 页面事件阶段（固定枚举）。 */
  const CLOUD_STAGES = Object.freeze([
    "accepted", "fetching", "starting", "navigating", "executing", "reporting",
    "succeeded", "failed", "paused", "offline"
  ]);

  /** popup 与 background 之间的执行控制消息类型（固定枚举）。 */
  const CLOUD_CONTROL_MESSAGE_TYPES = Object.freeze([
    "CLOUD_EXECUTION_STATUS",
    "CLOUD_EXECUTION_START",
    "CLOUD_EXECUTION_PAUSE"
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
    // 读取时同样校验并规范化 origin：任何畸形/越界地址都视为未绑定。
    const apiBase = normalizeApiBase(value.apiBase);
    if (!apiBase) return null;
    if (typeof value.token !== "string" || !value.token) return null;
    return {
      apiBase,
      installationId: typeof value.installationId === "string" ? value.installationId : "",
      token: value.token,
      tokenExpiresAt: typeof value.tokenExpiresAt === "string" ? value.tokenExpiresAt : "",
      device: value.device && typeof value.device === "object" ? value.device : null
    };
  }

  async function writeBoundState(state) {
    const apiBase = normalizeApiBase(state?.apiBase);
    if (!state || !apiBase) return false;
    return await writeStorage(STORAGE_KEYS.bound, {
      apiBase,
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
        status: ["SUCCESS", "FAILED", "PAUSED_NEED_USER"].includes(result.status) ? result.status : "FAILED",
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

  // ---------------------------------------------------------------- pause state

  /**
   * 执行暂停状态（P8）：用户从 popup 手动暂停或连续失败达到阈值后持久化
   * paused=true，只有用户显式「开始/恢复」才清除。不同绑定（重新绑定设备）
   * 时与执行状态一起清理，避免旧用户的暂停标记影响新绑定。
   */
  async function readPauseState() {
    const value = await readStorage(STORAGE_KEYS.pauseState);
    if (!value || typeof value !== "object") return { paused: false, reason: "", consecutiveFailures: 0, updatedAt: "" };
    return {
      paused: value.paused === true,
      reason: typeof value.reason === "string" ? value.reason.slice(0, 64) : "",
      consecutiveFailures: Number.isInteger(Number(value.consecutiveFailures))
        ? Math.max(0, Math.min(Number(value.consecutiveFailures), 99)) : 0,
      updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : ""
    };
  }

  async function writePauseState(state) {
    if (!state || typeof state !== "object") return false;
    return await writeStorage(STORAGE_KEYS.pauseState, {
      paused: state.paused === true,
      reason: String(state.reason || "").slice(0, 64),
      consecutiveFailures: Number.isInteger(Number(state.consecutiveFailures))
        ? Math.max(0, Math.min(Number(state.consecutiveFailures), 99)) : 0,
      updatedAt: String(state.updatedAt || new Date().toISOString())
    });
  }

  async function clearPauseState() {
    await removeStorage(STORAGE_KEYS.pauseState);
  }

  /** 成功重置失败计数，暂停标记一并清除。 */
  async function resetFailureCounter() {
    await writePauseState({ paused: false, reason: "", consecutiveFailures: 0 });
  }

  /** 连续失败 +1；达到 FAILURE_THRESHOLD 时返回 true（调用方执行 batch-pause）。 */
  async function recordFailure() {
    const state = await readPauseState();
    const next = state.consecutiveFailures + 1;
    await writePauseState({ paused: state.paused, reason: state.reason, consecutiveFailures: next });
    return next >= FAILURE_THRESHOLD;
  }

  /** 解除本机绑定：只清本机 Token 与执行状态，服务端撤销需在 Web 设备管理操作。 */
  async function unbindLocal() {
    await clearBoundState();
    await clearExecutionState();
    await clearRecentResults();
    await clearPauseState();
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

  /**
   * 校验并规范化 Cloud API 地址，非法输入返回 null：
   * - 本地：仅 http://localhost 或 http://127.0.0.1，且必须带明确端口；
   * - 远程：仅 https:// 合法 Origin，拒绝 userinfo/path/query/fragment，
   *   标准端口 443 归一化省略；远程 http 一律拒绝；
   * - 任何含空白、反斜杠或非 origin 形态的输入一律拒绝。
   */
  function normalizeApiBase(input) {
    if (typeof input !== "string") return null;
    const raw = input.trim();
    if (!raw || raw.length > 200 || /[\s\\]/.test(raw)) return null;
    let parsed;
    try {
      parsed = new URL(raw);
    } catch {
      return null;
    }
    if (parsed.username || parsed.password) return null;
    if (parsed.pathname !== "/" || parsed.search || parsed.hash) return null;
    const host = String(parsed.hostname || "").toLowerCase();
    if (!host) return null;
    const port = String(parsed.port || "");
    if (host === "localhost" || host === "127.0.0.1") {
      if (parsed.protocol !== "http:") return null;
      if (!port) return null;
      const localOrigin = `http://${host}:${port}`;
      return APPROVED_API_BASE_PRESETS.includes(localOrigin) ? localOrigin : null;
    }
    if (parsed.protocol !== "https:") return null;
    const remoteOrigin = port === "443" ? `https://${host}` : (port ? `https://${host}:${port}` : `https://${host}`);
    return remoteOrigin === "https://toudiniuma.cn" ? remoteOrigin : null;
  }

  function isAllowedApiBase(value) {
    return normalizeApiBase(value) === value;
  }

  function defaultApiBase() {
    return APPROVED_API_BASE_PRESETS[0];
  }

  function allowedApiBases() {
    return APPROVED_API_BASE_PRESETS.slice();
  }

  /** 是否为本地开发入口（http://localhost|127.0.0.1:port）。 */
  function isLocalApiBase(value) {
    const normalized = normalizeApiBase(value);
    return Boolean(normalized && normalized.startsWith("http://"));
  }

  /**
   * 绑定/调用前按需请求精确 `${origin}/*` host 权限（MV3 动态权限）：
   * 已授权直接通过；未授权时通过 chrome.permissions.request 触发用户确认，
   * 拒绝则 fail-closed（返回 false，调用方不得保存 Token）。无权限 API 的
   * 环境（非扩展测试上下文）按放行处理，由网络层兜底。
   */
  async function ensureHostPermission(origin) {
    const normalized = normalizeApiBase(origin);
    if (!normalized) return false;
    const permissionsApi = globalThis.chrome?.permissions;
    if (!permissionsApi || typeof permissionsApi.contains !== "function") return true;
    const pattern = `${normalized}/*`;
    try {
      if (await permissionsApi.contains({ origins: [pattern] })) return true;
      return Boolean(await permissionsApi.request({ origins: [pattern] }));
    } catch {
      return false;
    }
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
    // 每次调用前都校验 origin 格式：畸形/越界地址绝不发出任何网络请求。
    const normalizedBase = normalizeApiBase(apiBase);
    if (!normalizedBase) {
      return {
        success: false,
        httpStatus: 0,
        code: "VALIDATION_ERROR",
        message: "云端 API 地址无效",
        retryable: false
      };
    }
    const method = options.method || "GET";
    const timeoutMs = Math.max(1000, Math.min(Number(options.timeoutMs) || API_TIMEOUT_MS, 120000));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const headers = { Accept: "application/json" };
    if (options.token) headers.Authorization = `Bearer ${options.token}`;
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
    try {
      const response = await fetch(`${normalizedBase}${path}`, {
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

  /**
   * 批量暂停：当前 user + device 的全部 RUNNING 任务转 PAUSED_NEED_USER。
   * 自然幂等，无需 Idempotency-Key；要求 tasks:write scope。
   */
  async function batchPause(apiBase, token, reason) {
    return await requestCloudApi(apiBase, "/api/plugin/tasks/batch-pause", {
      method: "POST",
      token,
      body: { reason: String(reason || "USER_REQUESTED").slice(0, 64) }
    });
  }

  /** 心跳：强制刷新设备 last_seen_at 并返回服务端当前状态。 */
  async function heartbeat(apiBase, token) {
    return await requestCloudApi(apiBase, "/api/plugin/heartbeat", {
      method: "POST",
      token
    });
  }

  /** 上传单个已采集岗位；服务端按 (user, platform, platformJobId) 去重。 */
  async function captureJob(apiBase, token, payload) {
    return await requestCloudApi(apiBase, "/api/plugin/jobs/capture", {
      method: "POST",
      token,
      body: payload
    });
  }

  /**
   * 批量上传已采集岗位（单批最多 100 条，由调用方分片）。每条在发送前都做
   * allowlist 投影：队列元数据（queuedAt/uploadAttempts）与未知字段绝不进入
   * HTTP body。
   */
  async function captureJobsBatch(apiBase, token, items) {
    const projected = (Array.isArray(items) ? items : [])
      .map((item) => projectCapturePayload(item))
      .filter(Boolean);
    if (!projected.length) {
      return {
        success: false,
        httpStatus: 0,
        code: "VALIDATION_ERROR",
        message: "没有可上传的岗位条目",
        retryable: false
      };
    }
    return await requestCloudApi(apiBase, "/api/plugin/jobs/batch-capture", {
      method: "POST",
      token,
      body: { items: projected }
    });
  }

  // ---------------------------------------------------------------- capture sanitizer

  const CAPTURE_BATCH_LIMIT = 100;
  const CAPTURE_QUEUE_LIMIT = 200;
  /** 服务端字段上限；本地清洗必须与服务端一致，避免整条被拒。 */
  const CAPTURE_LIMITS = Object.freeze({
    platformJobId: 160, jobUrl: 2000, title: 240, salary: 120, city: 120,
    district: 120, companyName: 240, companySize: 80, industry: 120,
    experience: 120, education: 120, hrName: 120, jobDescription: 20000,
    benefit: 80, benefits: 30
  });

  /** 本地敏感内容预检：命中即拒绝整条，绝不把可疑文本发给云端。 */
  const CAPTURE_FORBIDDEN_PATTERN = /cookie|authorization|bearer|password|token=|localstorage|sessionstorage|securityid|encryptbossid|lid=|accesskey|secretkey|x-api-key/i;

  function cleanCaptureText(value) {
    return String(value ?? "")
      .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/[\u0000-\u001f\u007f]/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }
  function cleanCaptureDescription(value) {
    return cleanCaptureText(String(value ?? "")
      .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/https?:\/\/\S+/gi, " "));
  }

  function cleanCaptureBenefits(value) {
    if (!Array.isArray(value)) return [];
    const cleaned = [];
    for (const item of value) {
      const text = cleanCaptureText(String(item ?? "").replace(/<[^>]+>/g, " "));
      if (!text || text.length > CAPTURE_LIMITS.benefit) continue;
      cleaned.push(text);
    }
    return cleaned.slice(0, CAPTURE_LIMITS.benefits);
  }

  /**
   * 由内容脚本采集字段构造服务端白名单契约负载：
   * 只保留固定字段、限长、去 HTML/控制字符；必填字段缺失返回 valid=false。
   * securityId/lid/encryptBossId/Cookie/账号密码等一律不进入负载。
   * companyName 与 job_posts 非空约束一致，缺失即拒绝。
   */
  function buildCapturePayload(raw, platform, nowIso) {
    if (!raw || typeof raw !== "object") return { valid: false };
    const platformValue = cleanCaptureText(raw.platform || platform || "");
    const platformJobId = cleanCaptureText(raw.platformJobId || raw.jobId || "").slice(0, CAPTURE_LIMITS.platformJobId);
    // 岗位 URL 先剥离 query/fragment（securityId/lid 等参数绝不离开扩展），
    // 再通过受信任判定归一化；不可信 URL 直接拒绝整条。
    const rawUrl = cleanCaptureText(raw.jobUrl || raw.currentUrl || "");
    const jobUrl = isTrustedJobUrl(String(rawUrl).split(/[?#]/)[0], platformValue);
    const title = cleanCaptureText(raw.title).slice(0, CAPTURE_LIMITS.title);
    const companyName = cleanCaptureText(raw.companyName || raw.company).slice(0, CAPTURE_LIMITS.companyName);
    if (!platformValue || !platformJobId || !jobUrl || !title || !companyName) return { valid: false };
    const payload = {
      platform: platformValue,
      platformJobId,
      jobUrl,
      title,
      salary: cleanCaptureText(raw.salary).slice(0, CAPTURE_LIMITS.salary) || undefined,
      city: cleanCaptureText(raw.city || raw.location).slice(0, CAPTURE_LIMITS.city) || undefined,
      district: cleanCaptureText(raw.district).slice(0, CAPTURE_LIMITS.district) || undefined,
      companyName,
      companySize: cleanCaptureText(raw.companySize || raw.companyScale).slice(0, CAPTURE_LIMITS.companySize) || undefined,
      industry: cleanCaptureText(raw.industry).slice(0, CAPTURE_LIMITS.industry) || undefined,
      experience: cleanCaptureText(raw.experience).slice(0, CAPTURE_LIMITS.experience) || undefined,
      education: cleanCaptureText(raw.education || raw.degree).slice(0, CAPTURE_LIMITS.education) || undefined,
      benefits: cleanCaptureBenefits(raw.benefits),
      jobDescription: cleanCaptureDescription(raw.jobDescription || raw.description).slice(0, CAPTURE_LIMITS.jobDescription) || undefined,
      hrName: cleanCaptureText(raw.hrName).slice(0, CAPTURE_LIMITS.hrName) || undefined,
      capturedAt: String(nowIso || raw.capturedAt || new Date().toISOString())
    };
    const probe = JSON.stringify(payload);
    if (CAPTURE_FORBIDDEN_PATTERN.test(probe)) return { valid: false };
    return { valid: true, payload };
  }

  /** 服务端 capture 契约的完整字段白名单（顺序即 HTTP body 键集合）。 */
  const CAPTURE_CONTRACT_FIELDS = Object.freeze([
    "platform", "platformJobId", "jobUrl", "title", "salary", "city", "district",
    "companyName", "companySize", "industry", "experience", "education",
    "benefits", "jobDescription", "hrName", "capturedAt"
  ]);

  /**
   * 发送前的逐条 allowlist 投影：只保留契约字段，queuedAt、uploadAttempts
   * 或任何未知/多余字段（securityId/userId/Cookie/HTML 等）绝不进入
   * HTTP body。必填字段缺失返回 null（该条目无法上传）。
   */
  function projectCapturePayload(item) {
    if (!item || typeof item !== "object") return null;
    const projected = {};
    for (const key of CAPTURE_CONTRACT_FIELDS) {
      if (item[key] !== undefined && item[key] !== null) projected[key] = item[key];
    }
    if (!projected.platform || !projected.platformJobId || !projected.jobUrl
        || !projected.title || !projected.companyName || !projected.capturedAt) {
      return null;
    }
    return projected;
  }

  function sleepMs(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * 单条岗位上传：网络错误/429/5xx 为可重试失败（线性退避），重试耗尽或
   * 不可重试失败（400 校验/401/403）原样返回。返回结果带 attempts 次数，
   * 供用户界面展示重试情况；服务端按 (user, platform, platformJobId)
   * 幂等去重，重试绝不会产生新数据。
   */
  async function uploadCaptureItemWithRetry(apiBase, token, payload, options = {}) {
    const maxAttempts = Math.max(1, Math.min(Number(options.maxAttempts) || 3, 5));
    let last = null;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const result = await captureJob(apiBase, token, payload);
      if (result.success || !isRetryableCloudFailure(result)) {
        return { ...result, attempts: attempt };
      }
      last = { ...result, attempts: attempt };
      if (attempt < maxAttempts) {
        await sleepMs(300 * attempt);
      }
    }
    return last;
  }

  /** 采集队列：local-only，按 platformJobId 去重、限量、只存清洗后的负载。 */
  async function readCaptureQueue() {
    const value = await readStorage(STORAGE_KEYS.captureQueue);
    if (!Array.isArray(value)) return [];
    return value.filter((item) => item && typeof item === "object")
      .slice(0, CAPTURE_QUEUE_LIMIT);
  }

  /**
   * 入队前先做契约投影：只保留允许字段并加本地 queuedAt 元数据；
   * queuedAt/uploadAttempts 仅存在于本地队列，发送时会被再次投影剔除。
   */
  async function enqueueCapture(payload) {
    const projected = projectCapturePayload(payload);
    if (!projected) return false;
    const existing = await readCaptureQueue();
    const next = [
      { ...projected, queuedAt: new Date().toISOString() },
      ...existing.filter((item) => !(item.platform === projected.platform && item.platformJobId === projected.platformJobId))
    ].slice(0, CAPTURE_QUEUE_LIMIT);
    return await writeStorage(STORAGE_KEYS.captureQueue, next);
  }

  /** 记录失败条目的累计重试次数（本地队列保留该条目供用户展示/重试）。 */
  async function markCaptureItemAttempts(item, attempts) {
    if (!item || !String(item.platform || "") || !String(item.platformJobId || "")) return;
    const increment = Math.max(0, Math.min(Number(attempts) || 0, 999));
    const existing = await readCaptureQueue();
    const next = existing.map((entry) => {
      if (entry.platform === item.platform && entry.platformJobId === item.platformJobId) {
        const previous = Math.max(0, Math.min(Number(entry.uploadAttempts) || 0, 999));
        return { ...entry, uploadAttempts: Math.min(999, previous + increment) };
      }
      return entry;
    });
    await writeStorage(STORAGE_KEYS.captureQueue, next);
  }

  /** 移除指定 (platform, platformJobId) 队列条目（批量上传成功后清理）。 */
  async function removeCaptureQueueItems(items) {
    const keys = new Set((items || []).map((item) => `${String(item?.platform || "")}\u0000${String(item?.platformJobId || "")}`));
    const existing = await readCaptureQueue();
    const next = existing.filter((item) => !keys.has(`${String(item.platform || "")}\u0000${String(item.platformJobId || "")}`));
    return await writeStorage(STORAGE_KEYS.captureQueue, next);
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
    ALLOWED_API_BASES: APPROVED_API_BASE_PRESETS,
    CUSTOM_API_BASE_VALUE,
    ALLOWED_WAKE_PAGE_ORIGINS,
    STORAGE_KEYS,
    WAKE_MESSAGE_TYPE,
    PENDING_LIMIT,
    FAILURE_THRESHOLD,
    CONTENT_FAILURE_TYPES,
    CONTENT_RESULT_CODES,
    CONTENT_PAGE_STATES,
    REPORT_MAP,
    CLOUD_STAGES,
    CLOUD_CONTROL_MESSAGE_TYPES,
    // 存储
    readBoundState,
    writeBoundState,
    clearBoundState,
    readExecutionState,
    writeExecutionState,
    clearExecutionState,
    readRecentResults,
    appendRecentResult,
    readPauseState,
    writePauseState,
    clearPauseState,
    resetFailureCounter,
    recordFailure,
    unbindLocal,
    // 随机
    randomInstallationId,
    randomExecutionId,
    randomIdempotencyKey,
    // 校验
    normalizeApiBase,
    isAllowedApiBase,
    isLocalApiBase,
    ensureHostPermission,
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
    batchPause,
    heartbeat,
    captureJob,
    captureJobsBatch,
    shouldClearTokenOnCode,
    isRetryableCloudFailure,
    // 采集清洗与队列
    CAPTURE_BATCH_LIMIT,
    CAPTURE_QUEUE_LIMIT,
    CAPTURE_CONTRACT_FIELDS,
    buildCapturePayload,
    projectCapturePayload,
    readCaptureQueue,
    enqueueCapture,
    markCaptureItemAttempts,
    removeCaptureQueueItems,
    uploadCaptureItemWithRetry,
    // 报告与事件
    normalizeContentResult,
    buildReportPayload,
    reportEntryFor,
    cloudEvent,
    isExecutionActive,
    isLeaseExpired
  });
})();
