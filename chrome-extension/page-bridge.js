(function () {
  const SOURCE = "GET_JOBS_PAGE";
  const TARGET = "GET_JOBS_EXTENSION";
  const BRIDGE_VERSION = "2026-07-15-boss-api-poc-1";
  const ALLOWED_PAGE_ORIGINS = new Set([
    "http://localhost:6866",
    "http://127.0.0.1:6866",
    "http://localhost:8080",
    "http://127.0.0.1:8080"
  ]);
  const ALLOWED_PAGE_MESSAGE_TYPES = new Set([
    "GET_JOBS_EXTENSION_PING",
    "BOSS_PAGE_STATUS",
    "BOSS_DEBUG_COLLECT",
    "BOSS_COLLECT_CURRENT_PAGE",
    "BOSS_API_POC_COLLECT",
    "BOSS_SCAN_STATUS",
    "BOSS_SCAN_START",
    "BOSS_SCAN_STOP",
    "BOSS_DELIVER_ONE",
    "BOSS_DELIVER_BATCH",
    "ZHILIAN_SCAN_STATUS",
    "ZHILIAN_SCAN_START",
    "ZHILIAN_SCAN_STOP",
    "ZHILIAN_DELIVER_ONE",
    "ZHILIAN_DELIVER_BATCH",
    "CLOUD_DELIVERY_WAKE"
  ]);
  const ALLOWED_EXTENSION_MESSAGE_TYPES = new Set([
    "GET_JOBS_EXTENSION_EVENT"
  ]);
  const CLOUD_WAKE_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const CLOUD_WAKE_REQUEST_ID_PATTERN = /^[A-Za-z0-9._-]+$/;
  const CLOUD_WAKE_REQUEST_ID_MAX_LENGTH = 128;

  postToPage({ source: TARGET, type: "GET_JOBS_EXTENSION_READY", version: BRIDGE_VERSION });

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    if (!isAllowedPageOrigin(event.origin)) return;
    const message = sanitizePageMessage(event.data);
    if (!message) return;

    chrome.runtime.sendMessage(message, (response) => {
      const lastError = chrome.runtime.lastError?.message || "";
      // Cloud 唤醒的扩展无响应结果只回稳定 success/code/message，不透出
      // rawMessage 或未知原始 runtime 错误；legacy 消息保持兼容。
      const fallback = message.type === "CLOUD_DELIVERY_WAKE"
        ? {
            success: false,
            code: "EXTENSION_UNAVAILABLE",
            message: normalizeLastError(lastError)
          }
        : {
            success: false,
            message: normalizeLastError(lastError),
            rawMessage: lastError
          };
      postToPage({
        source: TARGET,
        requestId: message.requestId,
        type: `${message.type}_RESPONSE`,
        version: BRIDGE_VERSION,
        response: response || fallback
      });
    });
  });

  chrome.runtime.onMessage.addListener((message) => {
    if (!message || message.source !== "GET_JOBS_BACKGROUND") return;
    if (!ALLOWED_EXTENSION_MESSAGE_TYPES.has(message.type)) return;
    postToPage({
      source: TARGET,
      type: message.type,
      version: BRIDGE_VERSION,
      payload: message.payload
    });
  });

  function postToPage(payload) {
    const targetOrigin = window.location.origin;
    if (!isAllowedPageOrigin(targetOrigin)) return;
    window.postMessage(payload, targetOrigin);
  }

  function isAllowedPageOrigin(origin) {
    return ALLOWED_PAGE_ORIGINS.has(origin);
  }

  function isValidPageMessage(message) {
    return Boolean(
      message
        && message.source === SOURCE
        && typeof message.type === "string"
        && ALLOWED_PAGE_MESSAGE_TYPES.has(message.type)
    );
  }

  /**
   * 进入扩展后台前的页面消息校验与收敛。legacy 消息保持现状；
   * CLOUD_DELIVERY_WAKE 额外要求严格 UUID taskId 与有界 requestId，并且只
   * 转发 source/type/taskId/requestId 四个字段，防止任意网页上下文把
   * Token/URL/greeting/lease/executionId 等无界数据透传进扩展。background
   * 仍会独立二次校验，不依赖这里的校验。
   */
  function sanitizePageMessage(message) {
    if (!isValidPageMessage(message)) return null;
    if (message.type !== "CLOUD_DELIVERY_WAKE") return message;
    const taskId = typeof message.taskId === "string" ? message.taskId.trim() : "";
    const requestId = typeof message.requestId === "string" ? message.requestId.trim() : "";
    if (!CLOUD_WAKE_UUID_PATTERN.test(taskId)) return null;
    if (!requestId
        || requestId.length > CLOUD_WAKE_REQUEST_ID_MAX_LENGTH
        || !CLOUD_WAKE_REQUEST_ID_PATTERN.test(requestId)) return null;
    return {
      source: SOURCE,
      type: message.type,
      taskId: taskId.toLowerCase(),
      requestId
    };
  }

  function normalizeLastError(message) {
    if (!message) return "扩展无响应，请在 Chrome 扩展管理页重新加载 投递牛马 Cloud Bridge 后刷新本页面。";
    if (message.includes("Receiving end does not exist") || message.includes("Could not establish connection")) {
      return "Chrome扩展后台未接收到请求。请确认在你自己的 Chrome 中已加载并重新加载 chrome-extension 目录，然后刷新当前配置页面。";
    }
    if (message.includes("Extension context invalidated")) {
      return "Chrome扩展刚刚被重新加载，请刷新当前配置页面后再试。";
    }
    return message;
  }
})();
