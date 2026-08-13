if (typeof importScripts === "function") {
  importScripts("cloud-client.js");
}

const PLATFORM_CONFIG = {
  boss: {
    hosts: ["zhipin.com"],
    home: "https://www.zhipin.com/",
    contentScript: "boss-content.js",
    contentScripts: [
      "boss-selectors.js",
      "boss-debug.js",
      "boss-scan-support.js",
      "boss-api-collector.js",
      "boss-search-collector.js",
      "boss-detail-collector.js",
      "boss-content.js"
    ]
  },
  zhilian: {
    hosts: ["zhaopin.com"],
    home: "https://www.zhaopin.com/",
    contentScript: "zhilian-content.js",
    contentScripts: [
      "zhilian-scan-support.js",
      "zhilian-content.js"
    ]
  }
};

const pageTabs = new Map();
let scanSessionsWriteQueue = Promise.resolve();
const SCAN_SESSIONS_STORAGE_KEY = "__GET_JOBS_PLATFORM_SCAN_SESSIONS__";
const SCAN_SESSION_TTL_MS = 24 * 60 * 60 * 1000;
const PLATFORM_SHARED_SCAN_KEYS = {
  boss: ["__GET_JOBS_BOSS_SHARED_SCAN_TASK__", "__GET_JOBS_BOSS_SHARED_SCAN_CANCEL__"],
  zhilian: ["__GET_JOBS_ZHILIAN_SHARED_SCAN_TASK__", "__GET_JOBS_ZHILIAN_SHARED_SCAN_CANCEL__"]
};
const BACKGROUND_VERSION = "2026-08-01-consolidated-scan-fix";
const CONTENT_READY_RETRIES = 12;
const CONTENT_READY_INTERVAL_MS = 250;
const TAB_LOAD_TIMEOUT_MS = 10000;
const DELIVERY_NAVIGATION_TIMEOUT_MS = 15000;
const REQUIRED_BOSS_CONTENT_VERSION = "2026-08-01-consolidated-boss-api";
const REQUIRED_ZHILIAN_CONTENT_VERSION = "2026-07-29-zhilian-security-resume-fix";
const LOCAL_API_BASE_URLS = ["http://localhost:6866", "http://127.0.0.1:6866", "http://localhost:8888", "http://127.0.0.1:8888"];
const BOSS_LOCAL_API_MAX_ATTEMPTS = 3;
const BOSS_LOCAL_API_TIMEOUT_MS = 30000;
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

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.source === "GET_JOBS_BOSS_CONTENT" && message.type === "BOSS_API_PAGE_REQUEST") {
    if (!isBossSender(sender)) {
      sendResponse({ success: false, message: "拒绝非 Boss 页面发起搜索 API 请求" });
      return;
    }
    handleBossApiPageRequest(message, sender).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_BOSS_CONTENT" && message.type === "BOSS_SCAN_OWNER_STATUS") {
    if (!isBossSender(sender)) {
      sendResponse({ success: false, isOwner: false });
      return;
    }
    handleScanOwnerStatus("boss", sender).then(sendResponse).catch(() => {
      sendResponse({ success: false, isOwner: false });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_ZHILIAN_CONTENT" && message.type === "ZHILIAN_SCAN_OWNER_STATUS") {
    if (!isZhilianSender(sender)) {
      sendResponse({ success: false, isOwner: false });
      return;
    }
    handleScanOwnerStatus("zhilian", sender).then(sendResponse).catch(() => {
      sendResponse({ success: false, isOwner: false });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_BOSS_CONTENT" && message.type === "BOSS_LOCAL_API") {
    if (!isBossSender(sender)) {
      sendResponse({ success: false, message: "拒绝非 Boss 页面发起的本地接口请求" });
      return;
    }
    handleBossLocalApiRequest(message).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_ZHILIAN_CONTENT" && message.type === "ZHILIAN_LOCAL_API") {
    if (!isZhilianSender(sender)) {
      sendResponse({ success: false, message: "拒绝非智联页面发起的本地接口请求" });
      return;
    }
    handleZhilianLocalApiRequest(message).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_BOSS_CONTENT" && message.type === "BOSS_NAVIGATE_TAB") {
    if (!isBossSender(sender)) {
      sendResponse({ success: false, message: "拒绝非 Boss 页面发起的导航请求" });
      return;
    }
    handleBossContentNavigation(message, sender).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_ZHILIAN_CONTENT" && message.type === "ZHILIAN_NAVIGATE_TAB") {
    if (!isZhilianSender(sender)) {
      sendResponse({ success: false, message: "拒绝非智联页面发起的导航请求" });
      return;
    }
    handleZhilianContentNavigation(message, sender).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_PAGE") {
    if (!isAllowedPageSender(sender)) {
      sendResponse({ success: false, message: "当前页面来源不允许连接 Chrome Bridge" });
      return;
    }
    if (!isValidPageMessage(message)) {
      sendResponse({ success: false, message: "Chrome Bridge 请求类型不被允许" });
      return;
    }
    handlePageMessage(message, sender).then(sendResponse).catch((error) => {
      sendResponse({ success: false, message: error.message || String(error) });
    });
    return true;
  }

  if (message?.source === "GET_JOBS_PLATFORM") {
    forwardPlatformEvent(message, sender).catch(() => {});
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  pageTabs.delete(tabId);
  clearScanSession("boss", tabId).catch(() => {});
  clearScanSession("zhilian", tabId).catch(() => {});
});

async function forwardPlatformEvent(message, sender) {
  if (!isSupportedPlatformSender(sender)) return;
  const platform = isBossSender(sender) ? "boss" : "zhilian";
  const payload = {
    timestamp: Date.now(),
    ...message.payload,
    platform: message.payload?.platform || platform
  };
  await updateScanSessionFromEvent(platform, sender.tab?.id, payload);
  await broadcastPlatformEvent(payload, message.pageTabId);
}

function isValidPageMessage(message) {
  return Boolean(
    message
      && message.source === "GET_JOBS_PAGE"
      && typeof message.type === "string"
      && ALLOWED_PAGE_MESSAGE_TYPES.has(message.type)
  );
}

function isAllowedPageSender(sender) {
  return isAllowedPageUrl(sender?.tab?.url || sender?.url || "");
}

function isAllowedPageUrl(url) {
  try {
    const parsed = new URL(url);
    return ALLOWED_PAGE_ORIGINS.has(parsed.origin);
  } catch {
    return false;
  }
}

function isSupportedPlatformSender(sender) {
  return isBossSender(sender) || isZhilianSender(sender);
}

async function handleBossApiPageRequest(message, sender) {
  const tabId = sender.tab?.id;
  if (!tabId) return { success: false, message: "Boss API POC 缺少标签页 ID" };

  const resolved = resolveBossApiPageRequest(message?.request);
  if (!resolved.success) return resolved;

  const results = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    func: requestBossSearchApiInMainWorld,
    args: [resolved.relativeUrl]
  });
  const result = Array.isArray(results) ? results[0]?.result : null;
  return result && typeof result === "object"
    ? result
    : { success: false, message: "Boss 页面主环境未返回 API 结果" };
}

function resolveBossApiPageRequest(request) {
  if (!request || request.path !== "/wapi/zpgeek/search/joblist.json") {
    return { success: false, message: "Boss API POC 仅允许岗位搜索接口" };
  }
  const input = request.params && typeof request.params === "object" ? request.params : {};
  const allowedKeys = new Set(["scene", "query", "city", "page", "pageSize", "jobType", "salary", "experience", "degree", "scale", "industry", "stage"]);
  if (Object.keys(input).some((key) => !allowedKeys.has(key))) {
    return { success: false, message: "Boss API POC 包含未允许的搜索参数" };
  }

  const scene = String(input.scene || "").trim();
  const query = String(input.query || "").replace(/\s+/g, " ").trim();
  const city = String(input.city || "").trim();
  const page = Number(input.page);
  const pageSize = Number(input.pageSize);
  if (scene !== "1") return { success: false, message: "Boss API POC scene 参数无效" };
  if (!query || query.length > 100) return { success: false, message: "Boss API POC 关键词无效" };
  if (!/^\d+$/.test(city) || city === "0") return { success: false, message: "Boss API POC 城市码无效" };
  if (page !== 1) return { success: false, message: "Boss API POC 仅支持第一页" };
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 10) {
    return { success: false, message: "Boss API POC pageSize 必须在 1 到 10 之间" };
  }

  const params = new URLSearchParams({ scene: "1", query, city, page: "1", pageSize: String(pageSize) });
  for (const key of ["jobType", "salary", "experience", "degree", "scale", "industry", "stage"]) {
    const value = String(input[key] || "").trim();
    if (!value) continue;
    if (!/^\d+(,\d+)*$/.test(value) || value.length > 200) {
      return { success: false, message: `Boss API POC ${key} 筛选参数无效` };
    }
    params.set(key, value);
  }
  return {
    success: true,
    relativeUrl: `/wapi/zpgeek/search/joblist.json?${params.toString()}`
  };
}

async function requestBossSearchApiInMainWorld(relativeUrl) {
  try {
    if (!/(^|\.)zhipin\.com$/i.test(window.location.hostname)) {
      return { success: false, message: "当前标签页不是 Boss 页面" };
    }
    const requestUrl = new URL(relativeUrl, window.location.origin);
    if (requestUrl.origin !== window.location.origin || requestUrl.pathname !== "/wapi/zpgeek/search/joblist.json") {
      return { success: false, message: "Boss 搜索 API 地址校验失败" };
    }

    const pageText = String(document.body?.innerText || document.body?.textContent || "").slice(0, 5000);
    const pageState = {
      isLoginPage: /\/web\/user\/?(?:login)?/i.test(window.location.pathname)
        || /登录后查看|请先登录|手机号登录|扫码登录/.test(pageText),
      isSecurityPage: /安全验证|验证码|滑块验证|访问过于频繁|异常访问/.test(pageText)
    };
    if (pageState.isLoginPage || pageState.isSecurityPage) {
      return { success: true, responseOk: false, httpStatus: 0, pageState };
    }

    const response = await fetch(requestUrl.href, {
      method: "GET",
      credentials: "include",
      headers: { Accept: "application/json, text/plain, */*" }
    });
    const text = await response.text();
    const responsePreview = text.slice(0, 5000);
    let responsePath = "";
    try {
      responsePath = new URL(response.url).pathname;
    } catch {
      responsePath = "";
    }
    const responsePageState = {
      isLoginPage: pageState.isLoginPage
        || /\/web\/user|\/login/i.test(responsePath)
        || /登录后查看|请先登录|手机号登录|扫码登录/.test(responsePreview),
      isSecurityPage: pageState.isSecurityPage
        || /安全验证|验证码|滑块验证|访问过于频繁|异常访问/.test(responsePreview)
    };
    let data = null;
    let parseError = "";
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      parseError = "Boss 搜索接口返回了非 JSON 内容";
    }
    return {
      success: true,
      responseOk: response.ok,
      httpStatus: response.status,
      finalUrl: response.url,
      contentType: response.headers.get("content-type") || "",
      data,
      parseError,
      pageState: responsePageState
    };
  } catch (error) {
    return { success: false, message: error?.message || String(error) };
  }
}

function isBossSender(sender) {
  return isBossUrl(sender?.tab?.url || sender?.url || "");
}

function isZhilianSender(sender) {
  return isZhilianUrl(sender?.tab?.url || sender?.url || "");
}

async function handleBossContentNavigation(message, sender) {
  const tabId = sender.tab?.id;
  const targetUrl = normalizeBossUrl(message?.url);
  if (!tabId) return { success: false, message: "缺少Boss标签页ID" };
  if (!targetUrl) return { success: false, message: "Boss页面链接为空或格式错误" };
  if (!isBossSearchUrl(targetUrl) && !isBossJobDetailUrl(targetUrl)) {
    return { success: false, message: `拒绝打开非Boss搜索页或岗位详情页：${targetUrl}` };
  }

  await chrome.tabs.update(tabId, { url: targetUrl });
  return { success: true, url: targetUrl };
}

async function handleZhilianContentNavigation(message, sender) {
  const tabId = sender.tab?.id;
  const targetUrl = normalizeZhilianUrl(message?.url);
  const navigationType = message?.navigationType === "search" ? "search" : "detail";
  if (!tabId) return { success: false, message: "缺少智联标签页ID" };
  if (!targetUrl) return { success: false, message: "智联详情链接为空或格式错误" };
  if (!isZhilianUrl(targetUrl)) return { success: false, message: `拒绝打开非智联页面：${targetUrl}` };
  if (navigationType === "search" && !isZhilianSearchUrl(targetUrl)) {
    return { success: false, message: `拒绝打开非智联搜索页：${targetUrl}` };
  }
  if (navigationType === "detail" && !isZhilianJobDetailUrl(targetUrl)) {
    return { success: false, message: `拒绝打开非智联岗位详情页：${targetUrl}` };
  }

  await chrome.tabs.update(tabId, { url: targetUrl });
  return { success: true, url: targetUrl, navigationType };
}

async function handleBossLocalApiRequest(message) {
  const endpoint = resolveBossLocalApiEndpoint(message);
  if (!endpoint.success) return endpoint;

  const result = await requestLocalApi(endpoint.path, {
    operation: String(message.operation || ""),
    method: endpoint.method,
    body: message.body,
    timeoutMs: normalizeLocalApiTimeout(message.timeoutMs),
    pageTabId: message.pageTabId,
    platform: "boss"
  });
  console.log("[GetJobs BG] Boss API result:", endpoint.path, "success:", result.success, "status:", result.httpStatus, "error:", result.message || "");
  return result;
}

async function handleZhilianLocalApiRequest(message) {
  const endpoint = resolveZhilianLocalApiEndpoint(message);
  if (!endpoint.success) return endpoint;

  const result = await requestLocalApi(endpoint.path, {
    operation: String(message.operation || ""),
    method: endpoint.method,
    body: message.body,
    timeoutMs: normalizeLocalApiTimeout(message.timeoutMs),
    pageTabId: message.pageTabId,
    platform: "zhilian"
  });
  console.log("[GetJobs BG] Zhilian API result:", endpoint.path, "success:", result.success, "status:", result.httpStatus, "error:", result.message || "");
  return result;
}

function resolveBossLocalApiEndpoint(message) {
  const operation = String(message?.operation || "");
  if (operation === "chrome-jobs-dedupe") {
    return { success: true, method: "POST", path: "/api/boss/chrome/jobs/dedupe" };
  }
  if (operation === "chrome-jobs") {
    return { success: true, method: "POST", path: "/api/boss/chrome/jobs" };
  }
  if (operation === "ai-keywords") {
    return { success: true, method: "POST", path: "/api/boss/ai-keywords" };
  }
  if (operation === "delivery-result") {
    const id = String(message?.params?.id || "").trim();
    if (!/^\d+$/.test(id)) {
      return { success: false, message: "Boss投递结果接口缺少有效岗位ID" };
    }
    return { success: true, method: "POST", path: `/api/boss/jobs/${encodeURIComponent(id)}/delivery-result` };
  }
  return { success: false, message: "Boss本地接口请求类型不被允许" };
}

function resolveZhilianLocalApiEndpoint(message) {
  const operation = String(message?.operation || "");
  if (operation === "chrome-jobs") {
    return { success: true, method: "POST", path: "/api/zhilian/chrome/jobs" };
  }
  if (operation === "delivery-result") {
    const id = String(message?.params?.id || "").trim();
    if (!/^[1-9]\d*$/.test(id)) {
      return { success: false, message: "智联投递结果接口缺少有效岗位ID" };
    }
    return { success: true, method: "POST", path: `/api/zhilian/jobs/${id}/delivery-result` };
  }
  return { success: false, message: "智联本地接口请求类型不被允许" };
}

async function requestLocalApi(path, options = {}) {
  let lastError = null;
  const method = options.method || "POST";
  const timeoutMs = options.timeoutMs || BOSS_LOCAL_API_TIMEOUT_MS;
  const requestOptions = {
    method,
    headers: { "Content-Type": "application/json" },
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  };

  for (let attempt = 1; attempt <= BOSS_LOCAL_API_MAX_ATTEMPTS; attempt++) {
    for (const baseUrl of LOCAL_API_BASE_URLS) {
      try {
        const response = await fetchWithTimeout(`${baseUrl}${path}`, requestOptions, timeoutMs);
        const data = await parseLocalApiResponse(response);
        if (response.ok) {
          return { success: true, httpStatus: response.status, data, attempt, baseUrl };
        }
        lastError = new Error(data?.message || `本地接口返回 HTTP ${response.status}`);
        if (!isRetryableLocalApiStatus(response.status)) {
          return {
            success: false,
            httpStatus: response.status,
            data,
            message: lastError.message,
            errorType: classifyLocalApiError(response.status, lastError.message),
            attempt,
            baseUrl
          };
        }
      } catch (error) {
        lastError = error;
      }
    }

    if (attempt < BOSS_LOCAL_API_MAX_ATTEMPTS) {
      await postLocalApiRetryProgress(options.platform || "boss", options.pageTabId, options.operation, attempt + 1, BOSS_LOCAL_API_MAX_ATTEMPTS, lastError);
      await sleep(350 * attempt);
    }
  }

  return {
    success: false,
    message: `本地服务请求失败，已自动重试 ${BOSS_LOCAL_API_MAX_ATTEMPTS} 次：${friendlyLocalApiError(lastError)}`,
    errorType: classifyLocalApiError(0, lastError?.message || String(lastError || ""))
  };
}

async function fetchWithTimeout(url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function parseLocalApiResponse(response) {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

function normalizeLocalApiTimeout(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 1000) return BOSS_LOCAL_API_TIMEOUT_MS;
  return Math.min(Math.floor(parsed), 120000);
}

function isRetryableLocalApiStatus(status) {
  return status === 408 || status >= 500;
}

function friendlyLocalApiError(error) {
  const message = error?.message || String(error || "");
  if (error?.name === "AbortError" || /abort/i.test(message)) return "请求超时，请确认本地服务仍在运行";
  if (/Failed to fetch|NetworkError|fetch/i.test(message)) return "无法连接本地服务，请确认 6866 端口正常";
  return message || "未知网络错误";
}

function classifyLocalApiError(status, message) {
  const text = String(message || "");
  if (status === 403 && /cors/i.test(text)) return "CORS_REJECTED";
  if (status === 401 || status === 403) return "LOCAL_API_FORBIDDEN";
  if (status === 404) return "LOCAL_API_NOT_FOUND";
  if (status === 408 || /abort|timeout|超时/i.test(text)) return "LOCAL_API_TIMEOUT";
  if (!status || /Failed to fetch|NetworkError|fetch|连接|无法连接/i.test(text)) return "LOCAL_SERVICE_UNAVAILABLE";
  if (status >= 500) return "LOCAL_API_SERVER_ERROR";
  return "LOCAL_API_ERROR";
}

async function postLocalApiRetryProgress(platform, pageTabId, operation, nextAttempt, totalAttempts, error) {
  if (!pageTabId || operation !== "chrome-jobs") return;
  const platformName = platform === "zhilian" ? "智联" : "Boss";
  await postPlatformProgress(pageTabId, {
    platform,
    type: "warning",
    message: `${platformName}本地服务请求失败，正在自动重试 ${nextAttempt}/${totalAttempts}：${friendlyLocalApiError(error)}`,
    operation: "scan",
    stage: "submitting"
  });
}

async function handlePageMessage(message, sender) {
  const pageTabId = sender.tab?.id;
  if (pageTabId) pageTabs.set(pageTabId, Date.now());

  if (message.type === "GET_JOBS_EXTENSION_PING") {
    return { success: true, message: "Chrome扩展已连接", version: BACKGROUND_VERSION };
  }

  if (message.type === "CLOUD_DELIVERY_WAKE") {
    return await handleCloudDeliveryWake(message, sender);
  }

  const platform = message.platform || inferPlatform(message.type);
  if (!platform || !PLATFORM_CONFIG[platform]) {
    return { success: false, message: "未知平台" };
  }

  if (platform === "zhilian" && message.type === "ZHILIAN_SCAN_START" && !normalizeZhilianKeywordList(readZhilianKeywordInput(message)).length) {
    return { success: false, message: "请至少填写一个搜索关键词" };
  }

  const config = PLATFORM_CONFIG[platform];
  const tab = await resolvePlatformTab(platform, message);
  if (!tab?.id) {
    const noTabIsExpected = isPassiveStatusMessage(message.type) || isPassiveStopMessage(message.type);
    return {
      success: noTabIsExpected,
      message: message.type === "BOSS_SCAN_STOP"
        ? "没有正在运行的Boss扫描任务"
        : message.type === "BOSS_DEBUG_COLLECT" || message.type === "BOSS_COLLECT_CURRENT_PAGE" || message.type === "BOSS_API_POC_COLLECT"
          ? "未找到已打开的Boss页面。请先在Chrome中手动打开Boss搜索结果页。"
          : "未找到已打开的平台页面",
      isRunning: false,
      stage: "idle"
    };
  }

  if (isPassiveStatusMessage(message.type)) {
    return await queryPassivePlatformStatus(tab.id, platform, message, pageTabId);
  }

  if (isPassiveStopMessage(message.type)) {
    return await sendPassiveStop(tab.id, platform, message, pageTabId);
  }

  if (isDeliverMessage(platform, message.type)) {
    return platform === "boss"
      ? await handleBossDeliver(tab, config, message, pageTabId)
      : await handleZhilianDeliver(tab, config, message, pageTabId);
  }

  await waitForSupportedTab(tab.id, config);
  await ensureContentScript(tab.id, config.contentScript);
  if (!isNoFocusPlatformMessage(message.type)) {
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
  }

  try {
    let scanSession = null;
    if (isScanStartMessage(message.type)) {
      scanSession = await registerScanSession(platform, tab.id, message.runId, pageTabId, message.scanOwnerToken);
    }
    const response = await chrome.tabs.sendMessage(tab.id, {
      ...toPlatformContentMessage(message, platform),
      source: "GET_JOBS_BACKGROUND",
      pageTabId,
      scanOwnerToken: scanSession?.ownerToken
    });
    if (scanSession && response?.success === false) {
      await clearScanSession(platform, tab.id);
    }
    return response || { success: true };
  } catch (error) {
    if (isScanStartMessage(message.type)) {
      await clearScanSession(platform, tab.id);
    }
    return {
      success: false,
      message: buildContentScriptError(platform, error)
    };
  }
}

function readZhilianKeywordInput(message) {
  const config = message?.config || {};
  if (Object.prototype.hasOwnProperty.call(message || {}, "keywords")) return message.keywords;
  if (Object.prototype.hasOwnProperty.call(config, "keywords")) return config.keywords;
  return config.keyword;
}

function normalizeZhilianKeywordList(value) {
  const keywords = [];
  const append = (rawValue) => {
    if (Array.isArray(rawValue)) {
      rawValue.forEach(append);
      return;
    }
    const raw = String(rawValue ?? "").trim();
    if (!raw) return;
    if (raw.startsWith("[") && raw.endsWith("]")) {
      try {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          parsed.forEach(append);
          return;
        }
      } catch {
        append(raw.slice(1, -1));
        return;
      }
    }
    raw.split(/[,，;；\n\r]+/).forEach((item) => {
      const keyword = item.replace(/\s+/g, " ").trim().replace(/^["']|["']$/g, "").trim();
      if (keyword && !keywords.some((existing) => existing.toLowerCase() === keyword.toLowerCase())) {
        keywords.push(keyword);
      }
    });
  };
  append(value);
  return keywords;
}

function inferPlatform(type) {
  if (type?.startsWith("BOSS_")) return "boss";
  if (type?.startsWith("ZHILIAN_")) return "zhilian";
  return "";
}

function isNoFocusPlatformMessage(type) {
  return type === "BOSS_SCAN_STATUS"
    || type === "BOSS_PAGE_STATUS"
    || type === "BOSS_DEBUG_COLLECT"
    || type === "BOSS_COLLECT_CURRENT_PAGE"
    || type === "BOSS_API_POC_COLLECT"
    || type === "BOSS_SCAN_STOP"
    || type === "ZHILIAN_SCAN_STATUS"
    || type === "ZHILIAN_SCAN_STOP";
}

function isNoCreatePlatformMessage(type) {
  return isNoFocusPlatformMessage(type);
}

function isPassiveStatusMessage(type) {
  return type === "BOSS_SCAN_STATUS" || type === "BOSS_PAGE_STATUS" || type === "ZHILIAN_SCAN_STATUS";
}

function isPassiveStopMessage(type) {
  return type === "BOSS_SCAN_STOP" || type === "ZHILIAN_SCAN_STOP";
}

function isScanStartMessage(type) {
  return type === "BOSS_SCAN_START" || type === "ZHILIAN_SCAN_START";
}

function isBossDeliverMessage(type) {
  return type === "BOSS_DELIVER_ONE" || type === "BOSS_DELIVER_BATCH";
}

function isZhilianDeliverMessage(type) {
  return type === "ZHILIAN_DELIVER_ONE" || type === "ZHILIAN_DELIVER_BATCH";
}

function isDeliverMessage(platform, type) {
  return platform === "boss" ? isBossDeliverMessage(type) : isZhilianDeliverMessage(type);
}

function platformStartUrl(message) {
  if (message?.startUrl) return message.startUrl;
  if (message?.type === "BOSS_DELIVER_ONE" && message?.task?.url) return message.task.url;
  if (message?.type === "BOSS_DELIVER_BATCH" && Array.isArray(message.tasks) && message.tasks[0]?.url) {
    return message.tasks[0].url;
  }
  if (message?.type === "ZHILIAN_DELIVER_ONE" && message?.task?.url) return normalizeZhilianUrl(message.task.url) || message.task.url;
  if (message?.type === "ZHILIAN_DELIVER_BATCH" && Array.isArray(message.tasks) && message.tasks[0]?.url) {
    return normalizeZhilianUrl(message.tasks[0].url) || message.tasks[0].url;
  }
  return undefined;
}

async function handleBossDeliver(tab, config, message, pageTabId) {
  if (message.type === "BOSS_DELIVER_ONE") {
    const result = await deliverBossTask(tab, config, message.task, message, pageTabId, 1, 1).catch(async (error) => {
      const errorMessage = error.message || String(error);
      await postBossDeliveryResult(message.task, false, classifyDeliveryFailure(errorMessage)).catch(() => {});
      return {
        success: false,
        message: errorMessage,
        failureType: classifyDeliveryFailure(errorMessage).failureType
      };
    });
    return result || { success: false, message: "Boss投递未返回结果" };
  }

  const tasks = Array.isArray(message.tasks) ? message.tasks : [];
  if (!tasks.length) {
    return { success: false, message: "Boss批量投递任务为空" };
  }

  let success = 0;
  let failed = 0;
  for (let index = 0; index < tasks.length; index++) {
    const task = tasks[index];
    const result = await deliverBossTask(tab, config, task, message, pageTabId, index + 1, tasks.length).catch(async (error) => {
      const errorMessage = error.message || String(error);
      await postBossDeliveryResult(task, false, classifyDeliveryFailure(errorMessage)).catch(() => {});
      return {
        success: false,
        message: errorMessage,
        failureType: classifyDeliveryFailure(errorMessage).failureType
      };
    });
    if (result?.success) success += 1;
    else failed += 1;
  }

  return {
    success: true,
    message: `Boss批量投递完成：成功${success}，失败${failed}`,
    successCount: success,
    failedCount: failed
  };
}

async function deliverBossTask(tab, config, task, message, pageTabId, index, total) {
  if (!task?.url || !task?.id) {
    if (task?.id) {
      await postBossDeliveryResult(task, false, classifyDeliveryFailure("投递任务缺少岗位链接或ID")).catch(() => {});
    }
    return { success: false, message: "投递任务缺少岗位链接或ID" };
  }

  postPlatformProgress(pageTabId, {
    platform: "boss",
    type: "info",
    message: `Boss Chrome准备打开投递岗位 ${index}/${total}：${task.companyName || ""} ${task.jobName || ""}`.trim(),
    operation: "deliver",
    stage: "navigating",
    keyword: task.jobName || task.title || "",
    keywordIndex: index,
    keywordTotal: total
  });
  const targetUrl = task.url;
  await navigatePlatformTab(tab.id, targetUrl, config, DELIVERY_NAVIGATION_TIMEOUT_MS, { bossJobUrl: targetUrl });
  await ensureContentScript(tab.id, config.contentScript);
  if (!isNoFocusPlatformMessage(message.type)) {
    const updatedTab = await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(updatedTab.windowId || tab.windowId, { focused: true }).catch(() => {});
  }

  try {
    return await sendBossDeliverCurrent(tab.id, message, task, pageTabId, index, total);
  } catch (error) {
    const errorMessage = buildContentScriptError("boss", error, "投递");
    const failure = classifyDeliveryFailure(errorMessage);
    await postBossDeliveryResult(task, false, failure).catch(() => {});
    return {
      success: false,
      message: failure.failureReason,
      failureType: failure.failureType
    };
  }
}

async function sendBossDeliverCurrent(tabId, message, task, pageTabId, index, total) {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await chrome.tabs.sendMessage(tabId, {
        ...message,
        type: "BOSS_DELIVER_CURRENT_V2",
        source: "GET_JOBS_BACKGROUND",
        task,
        pageTabId,
        deliveryIndex: index,
        deliveryTotal: total
      });
      if (response) return response;
      const fallback = await inferBossDeliveryAfterEmptyResponse(tabId, task);
      if (fallback.success) return fallback;
    } catch (error) {
      lastError = error;
      await sleep(500);
    }
  }
  throw lastError || new Error("Boss投递请求发送失败");
}

async function handleZhilianDeliver(tab, config, message, pageTabId) {
  if (message.type === "ZHILIAN_DELIVER_ONE") {
    const result = await deliverZhilianTask(tab, config, message.task, message, pageTabId, 1, 1).catch(async (error) => {
      const errorMessage = error.message || String(error);
      await postZhilianDeliveryResult(message.task, false, classifyZhilianDeliveryFailure(errorMessage)).catch(() => {});
      return {
        success: false,
        message: errorMessage,
        failureType: classifyZhilianDeliveryFailure(errorMessage).failureType
      };
    });
    return result || { success: false, message: "智联投递未返回结果" };
  }

  const tasks = Array.isArray(message.tasks) ? message.tasks : [];
  if (!tasks.length) {
    return { success: false, message: "智联批量投递任务为空" };
  }

  let success = 0;
  let failed = 0;
  for (let index = 0; index < tasks.length; index++) {
    const task = tasks[index];
    const result = await deliverZhilianTask(tab, config, task, message, pageTabId, index + 1, tasks.length).catch(async (error) => {
      const errorMessage = error.message || String(error);
      await postZhilianDeliveryResult(task, false, classifyZhilianDeliveryFailure(errorMessage)).catch(() => {});
      return {
        success: false,
        message: errorMessage,
        failureType: classifyZhilianDeliveryFailure(errorMessage).failureType
      };
    });
    if (result?.success) success += 1;
    else failed += 1;
  }

  return {
    success: true,
    message: `智联批量投递完成：成功${success}，失败${failed}`,
    successCount: success,
    failedCount: failed
  };
}

async function deliverZhilianTask(tab, config, task, message, pageTabId, index, total) {
  if (!task?.url || !task?.id) {
    if (task?.id) {
      await postZhilianDeliveryResult(task, false, classifyZhilianDeliveryFailure("投递任务缺少岗位链接或ID")).catch(() => {});
    }
    return { success: false, message: "投递任务缺少岗位链接或ID" };
  }

  const targetUrl = normalizeZhilianUrl(task.url);
  if (!targetUrl || !isZhilianJobDetailUrl(targetUrl)) {
    const failure = classifyZhilianDeliveryFailure(`拒绝打开非智联岗位详情页：${task.url || ""}`);
    await postZhilianDeliveryResult(task, false, failure).catch(() => {});
    return { success: false, message: failure.failureReason, failureType: failure.failureType };
  }

  postPlatformProgress(pageTabId, {
    platform: "zhilian",
    type: "info",
    message: `智联 Chrome准备打开投递岗位 ${index}/${total}：${task.companyName || ""} ${task.jobName || ""}`.trim(),
    operation: "deliver",
    stage: "navigating",
    keyword: task.jobName || task.title || "",
    keywordIndex: index,
    keywordTotal: total
  });
  await navigatePlatformTab(tab.id, targetUrl, config, DELIVERY_NAVIGATION_TIMEOUT_MS, { zhilianJobUrl: targetUrl });
  await ensureContentScript(tab.id, config.contentScript);
  if (!isNoFocusPlatformMessage(message.type)) {
    const updatedTab = await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(updatedTab.windowId || tab.windowId, { focused: true }).catch(() => {});
  }

  try {
    return await sendZhilianDeliverCurrent(tab.id, message, { ...task, url: targetUrl }, pageTabId, index, total);
  } catch (error) {
    const errorMessage = buildContentScriptError("zhilian", error, "投递");
    const failure = classifyZhilianDeliveryFailure(errorMessage);
    await postZhilianDeliveryResult(task, false, failure).catch(() => {});
    return {
      success: false,
      message: failure.failureReason,
      failureType: failure.failureType
    };
  }
}

async function sendZhilianDeliverCurrent(tabId, message, task, pageTabId, index, total) {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await chrome.tabs.sendMessage(tabId, {
        ...message,
        type: "ZHILIAN_DELIVER_CURRENT_V2",
        source: "GET_JOBS_BACKGROUND",
        task,
        pageTabId,
        deliveryIndex: index,
        deliveryTotal: total
      });
      if (response) return response;
      const fallback = await inferZhilianDeliveryAfterEmptyResponse(tabId, task);
      if (fallback.success) return fallback;
    } catch (error) {
      lastError = error;
      await sleep(500);
    }
  }
  throw lastError || new Error("智联投递请求发送失败");
}

async function inferZhilianDeliveryAfterEmptyResponse(tabId, task) {
  try {
    const tab = await chrome.tabs.get(tabId);
    const currentUrl = tab.url || tab.pendingUrl || "";
    if (isZhilianUrl(currentUrl)) {
      return {
        success: false,
        message: "智联投递未返回结果，请在详情页确认是否出现投递成功状态。"
      };
    }
    return { success: false, message: "智联投递未返回结果，当前标签页已离开智联页面。" };
  } catch {
    return { success: false, message: "智联投递未返回结果" };
  }
}

async function inferBossDeliveryAfterEmptyResponse(tabId, task) {
  try {
    const tab = await chrome.tabs.get(tabId);
    const currentUrl = tab.url || tab.pendingUrl || "";
    if (isBossChatUrl(currentUrl)) {
      await postBossDeliveryResult(task, true, "Boss已进入沟通页").catch(() => {});
      return {
        success: true,
        message: "Boss已进入沟通页，按成功处理。"
      };
    }
    return { success: false, message: "Boss投递未返回结果，未确认进入沟通页。" };
  } catch {
    return { success: false, message: "Boss投递未返回结果" };
  }
}

async function postBossDeliveryResult(task, success, message) {
  if (!task?.id) return;
  const failure = success ? null : normalizeFailurePayload(message);
  await fetch(`http://localhost:6866/api/boss/jobs/${task.id}/delivery-result`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      success,
      message: success ? message : failure.failureReason,
      failureType: failure?.failureType,
      failureReason: failure?.failureReason
    })
  });
}

async function postZhilianDeliveryResult(task, success, message) {
  if (!task?.id) return;
  const failure = success ? null : normalizeZhilianFailurePayload(message);
  await fetch(`http://localhost:6866/api/zhilian/jobs/${task.id}/delivery-result`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      success,
      message: success ? message : failure.failureReason,
      failureType: failure?.failureType,
      failureReason: failure?.failureReason
    })
  });
}

function classifyDeliveryFailure(message) {
  const text = String(message || "");
  let failureType = "UNKNOWN_ERROR";
  if (/(登录|重新登录|未登录|扫码|账号登录)/.test(text)) failureType = "LOGIN_EXPIRED";
  else if (/(安全验证|验证码|滑块|验证|风控|实名认证|账号异常|操作过于频繁)/.test(text)) failureType = "PLATFORM_VERIFICATION";
  else if (/(职位已关闭|停止招聘|职位不存在|该职位.*不存在|岗位关闭|已下线|暂停招聘)/.test(text)) failureType = "JOB_CLOSED";
  else if (/(已投递|已申请|已沟通|继续沟通|重复投递)/.test(text)) failureType = "ALREADY_DELIVERED";
  else if (/(未找到.*按钮|按钮不可点击|无法点击|不可点击|未出现聊天窗口|未出现沟通页|暂不接受沟通|无法与该职位沟通|缺少岗位链接|缺少.*ID)/.test(text)) failureType = "BUTTON_UNCLICKABLE";
  else if (/(网络|超时|timeout|fetch|HTTP|请求失败|连接失败|未返回结果|发送失败|未能打开)/i.test(text)) failureType = "NETWORK_ERROR";
  return { failureType, failureReason: text || "Boss投递失败" };
}

function normalizeFailurePayload(message) {
  if (message && typeof message === "object") {
    const reason = message.failureReason || message.message || "Boss投递失败";
    return { failureType: message.failureType || classifyDeliveryFailure(reason).failureType, failureReason: reason };
  }
  return classifyDeliveryFailure(message);
}

function classifyZhilianDeliveryFailure(message) {
  const text = String(message || "");
  let failureType = "UNKNOWN_ERROR";
  if (/(登录|重新登录|未登录|扫码|账号登录)/.test(text)) failureType = "LOGIN_EXPIRED";
  else if (/(安全验证|验证码|滑块|验证|风控|实名认证|账号异常|操作过于频繁)/.test(text)) failureType = "PLATFORM_VERIFICATION";
  else if (/(职位已关闭|停止招聘|职位不存在|岗位已下线|已暂停招聘|岗位关闭|已下线)/.test(text)) failureType = "JOB_CLOSED";
  else if (/(已投递|已申请|投递成功|申请成功|重复投递)/.test(text)) failureType = "ALREADY_DELIVERED";
  else if (/(未找到.*按钮|按钮不可点击|无法点击|不可点击|请先完善简历|请上传简历|缺少岗位链接|缺少.*ID|非智联岗位详情页)/.test(text)) failureType = "BUTTON_UNCLICKABLE";
  else if (/(网络|超时|timeout|fetch|HTTP|请求失败|连接失败|未返回结果|发送失败|未能打开)/i.test(text)) failureType = "NETWORK_ERROR";
  return { failureType, failureReason: text || "智联投递失败" };
}

function normalizeZhilianFailurePayload(message) {
  if (message && typeof message === "object") {
    const reason = message.failureReason || message.message || "智联投递失败";
    return { failureType: message.failureType || classifyZhilianDeliveryFailure(reason).failureType, failureReason: reason };
  }
  return classifyZhilianDeliveryFailure(message);
}

async function postPlatformProgress(pageTabId, payload) {
  await broadcastPlatformEvent({
    timestamp: Date.now(),
    ...payload
  }, pageTabId);
}

async function queryPassivePlatformStatus(tabId, platform, message, pageTabId) {
  if (message?.type === "BOSS_PAGE_STATUS" || platform === "zhilian") {
    try {
      await ensureContentScript(tabId, PLATFORM_CONFIG[platform].contentScript);
    } catch (error) {
      const navigationStatus = await buildRegisteredNavigationStatus(platform, tabId);
      if (navigationStatus) return navigationStatus;
      return {
        success: true,
        message: buildContentScriptError(platform, error),
        isRunning: false,
        hasStoredTask: false,
        stage: "idle"
      };
    }
  }

  if (!await pingContentScript(tabId)) {
    const navigationStatus = await buildRegisteredNavigationStatus(platform, tabId);
    if (navigationStatus) return navigationStatus;
    return {
      success: true,
      message: "平台页面脚本未就绪，状态按空闲处理。",
      isRunning: false,
      hasStoredTask: false,
      stage: "idle"
    };
  }

  try {
    const response = await chrome.tabs.sendMessage(tabId, {
      ...toPlatformContentMessage(message, platform),
      source: "GET_JOBS_BACKGROUND",
      pageTabId
    });
    if (response && isTerminalScanStatus(response)) {
      await clearScanSession(platform, tabId);
    }
    return response || { success: true, isRunning: false, stage: "idle" };
  } catch (error) {
    const navigationStatus = await buildRegisteredNavigationStatus(platform, tabId);
    if (navigationStatus) return navigationStatus;
    return {
      success: true,
      message: buildContentScriptError(platform, error),
      isRunning: false,
      hasStoredTask: false,
      stage: "idle"
    };
  }
}

async function buildRegisteredNavigationStatus(platform, tabId) {
  const session = await readScanSession(platform);
  if (!session || session.tabId !== tabId) return null;
  return {
    success: true,
    isRunning: true,
    hasStoredTask: true,
    temporaryUnavailable: true,
    stage: "navigating",
    runId: session.runId || "",
    message: `${platform === "boss" ? "Boss" : "智联"}扫描页面正在跳转，任务仍在后台继续。`
  };
}

async function sendPassiveStop(tabId, platform, message, pageTabId) {
  if (!await pingContentScript(tabId)) {
    await cleanupPlatformScanState(platform, tabId);
    return {
      success: true,
      message: platform === "boss" ? "没有正在运行的Boss扫描任务，后台旧扫描状态已清理" : "没有正在运行的扫描任务，后台旧扫描状态已清理",
      isRunning: false,
      stage: "stopped"
    };
  }

  try {
    const response = await chrome.tabs.sendMessage(tabId, {
      ...message,
      source: "GET_JOBS_BACKGROUND",
      pageTabId
    });
    if (response?.success !== false) {
      await cleanupPlatformScanState(platform, tabId);
    }
    return response || { success: true, isRunning: false, stage: "stopped" };
  } catch (error) {
    await cleanupPlatformScanState(platform, tabId);
    return {
      success: true,
      message: `${buildContentScriptError(platform, error)}；后台旧扫描状态已清理，请刷新页面后重新开始扫描。`,
      isRunning: false,
      stage: "stopped"
    };
  }
}

async function resolvePlatformTab(platform, message) {
  if (isPassiveStatusMessage(message.type) || isPassiveStopMessage(message.type)) {
    return await findRegisteredOrRunningScanTab(platform);
  }
  if (isDeliverMessage(platform, message.type)) {
    return await findDeliveryPlatformTab(platform, platformStartUrl(message));
  }
  if (isScanStartMessage(message.type)) {
    return await findScanPlatformTab(platform, platformStartUrl(message), message.runId);
  }
  if (isNoCreatePlatformMessage(message.type)) {
    return await findPlatformTab(platform);
  }
  return await findOrCreatePlatformTab(platform, platformStartUrl(message));
}

async function findScanPlatformTab(platform, startUrl, requestedRunId = "") {
  const registered = await getRegisteredScanTab(platform, requestedRunId);
  if (registered) return registered;

  const running = await findRunningPlatformTab(platform, requestedRunId);
  if (running) return running;

  return await findOrCreatePlatformTab(platform, startUrl);
}

async function findRegisteredOrRunningScanTab(platform) {
  const registered = await getRegisteredScanTab(platform);
  if (registered) return registered;
  return await findRunningPlatformTab(platform);
}

async function findDeliveryPlatformTab(platform, startUrl) {
  const scanTab = await findRegisteredOrRunningScanTab(platform);
  const scanStatus = scanTab?.id ? await probePlatformScanStatus(scanTab.id, platform) : null;
  const scanIsActive = isActiveScanStatus(scanStatus);
  const excludedTabIds = scanIsActive && scanTab?.id ? [scanTab.id] : [];

  if (!scanIsActive && scanTab?.id) {
    await clearScanSession(platform, scanTab.id);
  }

  return await findOrCreatePlatformTab(platform, startUrl, {
    excludedTabIds,
    active: true
  });
}

async function findOrCreatePlatformTab(platform, startUrl, options = {}) {
  const config = PLATFORM_CONFIG[platform];
  const excludedTabIds = new Set(options.excludedTabIds || []);
  const tabs = await chrome.tabs.query({});
  const found = tabs
    .filter((tab) => !excludedTabIds.has(tab.id))
    .filter((tab) => config.hosts.some((host) => (tab.url || tab.pendingUrl || "").includes(host)))
    .sort((left, right) => Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0))[0];
  if (found) return found;
  return await chrome.tabs.create({ url: startUrl || config.home, active: options.active !== false });
}

async function findPlatformTab(platform) {
  const config = PLATFORM_CONFIG[platform];
  const tabs = await chrome.tabs.query({});
  return tabs
    .filter((tab) => config.hosts.some((host) => (tab.url || tab.pendingUrl || "").includes(host)))
    .sort((left, right) => Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0))[0];
}

async function findRunningPlatformTab(platform, requestedRunId = "") {
  const config = PLATFORM_CONFIG[platform];
  const tabs = (await chrome.tabs.query({}))
    .filter((tab) => config.hosts.some((host) => (tab.url || tab.pendingUrl || "").includes(host)));

  for (const tab of tabs) {
    if (!tab.id) continue;
    const status = await probePlatformScanStatus(tab.id, platform);
    if (isActiveScanStatus(status)) {
      if (requestedRunId && !scanRunMatches(status?.runId, requestedRunId)) continue;
      await registerScanSession(platform, tab.id, status.runId, null, status.scanOwnerToken);
      return tab;
    }
  }
  return null;
}

async function probePlatformScanStatus(tabId, platform) {
  if (!await pingContentScript(tabId)) return null;
  try {
    const type = platform === "boss" ? "BOSS_SCAN_STATUS" : "ZHILIAN_SCAN_STATUS_V2";
    return await chrome.tabs.sendMessage(tabId, {
      source: "GET_JOBS_BACKGROUND",
      type
    });
  } catch {
    return null;
  }
}

function isActiveScanStatus(status) {
  return Boolean(
    status
      && (
        status.isRunning
        || status.hasStoredTask
        || status.paused
        || status.resumable
      )
  );
}

function isTerminalScanStatus(status) {
  return Boolean(
    status
      && !isActiveScanStatus(status)
      && ["complete", "stopped", "error", "idle"].includes(String(status.stage || ""))
  );
}

async function handleScanOwnerStatus(platform, sender) {
  const session = await readScanSession(platform);
  const tabId = sender.tab?.id;
  return {
    success: true,
    isOwner: Boolean(session && tabId && session.tabId === tabId),
    ownerToken: session?.ownerToken || "",
    runId: session?.runId || "",
    tabId: session?.tabId || null
  };
}

async function registerScanSession(platform, tabId, runId, pageTabId, ownerToken = "") {
  if (!platform || !tabId) return null;
  return await mutateScanSessions((sessions) => {
    const existing = sessions[platform];
    const session = {
      platform,
      tabId,
      runId: String(runId || existing?.runId || ""),
      pageTabId: pageTabId || existing?.pageTabId || null,
      ownerToken: String(
        ownerToken
          || (existing?.tabId === tabId ? existing?.ownerToken : "")
          || `${platform}-${tabId}-${Date.now()}-${Math.random().toString(16).slice(2)}`
      ),
      updatedAt: Date.now()
    };
    sessions[platform] = session;
    return session;
  });
}

async function readScanSession(platform) {
  const sessions = await readScanSessions();
  const session = sessions[platform];
  if (!session) return null;
  if (Date.now() - Number(session.updatedAt || 0) > SCAN_SESSION_TTL_MS) {
    await clearScanSession(platform, session.tabId);
    return null;
  }
  return session;
}

async function readScanSessions() {
  try {
    const result = await chrome.storage.local.get(SCAN_SESSIONS_STORAGE_KEY);
    const sessions = result?.[SCAN_SESSIONS_STORAGE_KEY];
    return sessions && typeof sessions === "object" ? { ...sessions } : {};
  } catch {
    return {};
  }
}

async function getRegisteredScanTab(platform, requestedRunId = "") {
  const session = await readScanSession(platform);
  if (!session?.tabId) return null;
  if (requestedRunId && !scanRunMatches(session.runId, requestedRunId)) {
    await cleanupPlatformScanState(platform, session.tabId);
    return null;
  }
  const tab = await chrome.tabs.get(session.tabId).catch(() => null);
  if (!tab || !isSupportedUrl(tab.url || tab.pendingUrl || "", PLATFORM_CONFIG[platform])) {
    await clearScanSession(platform, session.tabId);
    return null;
  }
  return tab;
}

async function clearScanSession(platform, expectedTabId = null) {
  await mutateScanSessions((sessions) => {
    const existing = sessions[platform];
    if (!existing) return;
    if (expectedTabId && existing.tabId !== expectedTabId) return;
    delete sessions[platform];
  });
}

async function cleanupPlatformScanState(platform, expectedTabId = null) {
  await clearScanSession(platform, expectedTabId);
  const keys = PLATFORM_SHARED_SCAN_KEYS[platform] || [];
  if (keys.length) {
    await chrome.storage.local.remove(keys).catch(() => {});
  }
}

function scanRunMatches(currentRunId, requestedRunId) {
  const current = String(currentRunId || "").trim();
  const requested = String(requestedRunId || "").trim();
  return Boolean(current && requested && current === requested);
}

async function mutateScanSessions(mutator) {
  const operation = scanSessionsWriteQueue.then(async () => {
    const sessions = await readScanSessions();
    const result = mutator(sessions);
    await chrome.storage.local.set({ [SCAN_SESSIONS_STORAGE_KEY]: sessions });
    return result;
  });
  scanSessionsWriteQueue = operation.catch(() => {});
  return await operation;
}

async function updateScanSessionFromEvent(platform, tabId, payload) {
  if (!tabId || payload?.operation !== "scan") return;
  if (["complete", "stopped", "error"].includes(String(payload.stage || ""))) {
    await clearScanSession(platform, tabId);
    return;
  }
  const session = await readScanSession(platform);
  if (!session || session.tabId !== tabId) return;
  await registerScanSession(platform, tabId, payload.runId || session.runId, session.pageTabId, session.ownerToken);
}

async function broadcastPlatformEvent(payload, preferredPageTabId = null) {
  const tabs = await chrome.tabs.query({});
  const targets = tabs
    .filter((tab) => tab.id && isAllowedPageUrl(tab.url || tab.pendingUrl || ""))
    .sort((left, right) => Number(right.id === preferredPageTabId) - Number(left.id === preferredPageTabId));

  await Promise.all(targets.map((tab) => chrome.tabs.sendMessage(tab.id, {
    source: "GET_JOBS_BACKGROUND",
    type: "GET_JOBS_EXTENSION_EVENT",
    payload
  }).catch(() => {})));
}

async function waitForSupportedTab(tabId, config) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < TAB_LOAD_TIMEOUT_MS) {
    const tab = await chrome.tabs.get(tabId);
    const url = tab.url || tab.pendingUrl || "";
    if (isSupportedUrl(url, config) && tab.status !== "loading") return tab;
    await sleep(CONTENT_READY_INTERVAL_MS);
  }

  const tab = await chrome.tabs.get(tabId);
  const url = tab.url || tab.pendingUrl || "";
  if (!isSupportedUrl(url, config)) {
    throw new Error("请先打开支持的招聘平台页面后再开始扫描。");
  }
  return tab;
}

async function navigatePlatformTab(tabId, url, config, timeoutMs, options = {}) {
  const currentTab = await chrome.tabs.get(tabId);
  const currentUrl = currentTab.url || currentTab.pendingUrl || "";
  if (!isSameNavigationUrl(currentUrl, url, options)) {
    await chrome.tabs.update(tabId, { url });
  }

  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const tab = await chrome.tabs.get(tabId);
    const tabUrl = tab.url || tab.pendingUrl || "";
    if (isSupportedUrl(tabUrl, config) && isSameNavigationUrl(tabUrl, url, options) && tab.status !== "loading") {
      return tab;
    }
    await sleep(CONTENT_READY_INTERVAL_MS);
  }

  const tab = await chrome.tabs.get(tabId);
  const tabUrl = tab.url || tab.pendingUrl || "";
  if (!isSupportedUrl(tabUrl, config)) {
    throw new Error("请先打开支持的招聘平台页面后再投递。");
  }
  throw new Error(`${platformDisplayNameByConfig(config)}页面未能打开目标岗位详情页。当前URL：${tabUrl || "未知"}`);
}

async function ensureContentScript(tabId, file) {
  if (await isContentScriptReady(tabId, file)) return;

  await chrome.scripting.executeScript({ target: { tabId }, files: contentScriptFiles(file) });

  for (let attempt = 0; attempt < CONTENT_READY_RETRIES; attempt++) {
    if (await isContentScriptReady(tabId, file)) return;
    await sleep(CONTENT_READY_INTERVAL_MS);
  }

  throw new Error("Chrome扩展已加载，但招聘页面脚本未就绪。请刷新招聘页面后重试。");
}

function contentScriptFiles(file) {
  if (file === "boss-content.js") {
    return PLATFORM_CONFIG.boss.contentScripts || [file];
  }
  if (file === "zhilian-content.js") {
    return PLATFORM_CONFIG.zhilian.contentScripts || [file];
  }
  return [file];
}

async function isContentScriptReady(tabId, file) {
  const ping = await pingContentScript(tabId);
  if (!ping) return false;
  if (file === "boss-content.js") return await isBossContentVersionReady(tabId);
  if (file === "zhilian-content.js") return await isZhilianContentVersionReady(tabId);
  return true;
}

async function pingContentScript(tabId) {
  try {
    return await chrome.tabs.sendMessage(tabId, { source: "GET_JOBS_BACKGROUND", type: "PING_CONTENT" });
  } catch {
    return null;
  }
}

async function isBossContentVersionReady(tabId) {
  try {
    const response = await chrome.tabs.sendMessage(tabId, {
      source: "GET_JOBS_BACKGROUND",
      type: "GET_BOSS_CONTENT_VERSION"
    });
    return response?.version === REQUIRED_BOSS_CONTENT_VERSION;
  } catch {
    return false;
  }
}

async function isZhilianContentVersionReady(tabId) {
  try {
    const response = await chrome.tabs.sendMessage(tabId, {
      source: "GET_JOBS_BACKGROUND",
      type: "GET_ZHILIAN_CONTENT_VERSION"
    });
    return response?.version === REQUIRED_ZHILIAN_CONTENT_VERSION;
  } catch {
    return false;
  }
}

function toPlatformContentMessage(message, platform) {
  const type = message?.type;
  if (platform === "zhilian" && isZhilianVersionedContentMessage(type)) {
    return { ...message, type: `${type}_V2` };
  }
  return message;
}

function isZhilianVersionedContentMessage(type) {
  return type === "ZHILIAN_SCAN_START"
    || type === "ZHILIAN_SCAN_STATUS"
    || type === "ZHILIAN_DELIVER_ONE"
    || type === "ZHILIAN_DELIVER_BATCH";
}

function isSupportedUrl(url, config) {
  return /^https?:\/\//.test(url) && config.hosts.some((host) => url.includes(host));
}

function platformDisplayNameByConfig(config) {
  if (config?.contentScript === "boss-content.js") return "Boss";
  if (config?.contentScript === "zhilian-content.js") return "智联";
  return "招聘平台";
}

function isSameNavigationUrl(left, right, options = {}) {
  if (options.bossJobUrl && sameBossJobDetailUrl(left, options.bossJobUrl)) return true;
  if (options.zhilianJobUrl && sameZhilianJobDetailUrl(left, options.zhilianJobUrl)) return true;
  try {
    const leftUrl = new URL(left);
    const rightUrl = new URL(right);
    return leftUrl.origin === rightUrl.origin && leftUrl.pathname === rightUrl.pathname;
  } catch {
    return String(left || "") === String(right || "");
  }
}

function sameBossJobDetailUrl(left, right) {
  const leftId = extractBossJobId(left);
  const rightId = extractBossJobId(right);
  return Boolean(leftId && rightId && leftId === rightId);
}

function sameZhilianJobDetailUrl(left, right) {
  const leftId = extractZhilianJobId(left);
  const rightId = extractZhilianJobId(right);
  return Boolean(leftId && rightId && leftId === rightId);
}

function isBossSearchUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:"
      && parsed.hostname.endsWith("zhipin.com")
      && (parsed.pathname === "/web/geek/job" || parsed.pathname === "/web/geek/jobs");
  } catch {
    return false;
  }
}

function normalizeBossUrl(url) {
  try {
    const parsed = new URL(String(url || ""), "https://www.zhipin.com");
    if (parsed.hostname.endsWith("zhipin.com") && parsed.protocol === "http:") {
      parsed.protocol = "https:";
    }
    parsed.hash = "";
    if (parsed.protocol !== "https:" || !parsed.hostname.endsWith("zhipin.com")) return "";
    return parsed.href;
  } catch {
    return "";
  }
}

function isBossJobDetailUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:"
      && parsed.hostname.endsWith("zhipin.com")
      && parsed.pathname.includes("/job_detail/")
      && Boolean(extractBossJobId(parsed.href));
  } catch {
    return false;
  }
}

function isBossUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" && parsed.hostname.endsWith("zhipin.com");
  } catch {
    return false;
  }
}

function isZhilianUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" && isZhilianHost(parsed.hostname);
  } catch {
    return false;
  }
}

function isZhilianSearchUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:"
      && isZhilianHost(parsed.hostname)
      && /^\/sou(?:\/|$)/i.test(parsed.pathname);
  } catch {
    return false;
  }
}

function isZhilianHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return host === "zhaopin.com" || host.endsWith(".zhaopin.com");
}

function normalizeZhilianUrl(url) {
  try {
    const parsed = new URL(String(url || ""));
    if (isZhilianHost(parsed.hostname) && parsed.protocol === "http:") {
      parsed.protocol = "https:";
    }
    parsed.hash = "";
    return parsed.href;
  } catch {
    return "";
  }
}

function isZhilianJobDetailUrl(url) {
  try {
    const parsed = new URL(url);
    const host = parsed.hostname.toLowerCase();
    const path = parsed.pathname.toLowerCase();
    const text = `${host}${path}${parsed.search.toLowerCase()}`;
    if (parsed.protocol !== "https:" || !isZhilianHost(host)) return false;
    if (/company|gongsi|qiye|enterprise|firm|business|corp/.test(`${host}${path}`)) return false;
    if (/^\/sou(\/|$)|\/search\/|\/company\/|\/gongsi\/|\/qiye\//.test(path)) return false;
    return host.startsWith("jobs.")
      || /\/job\/[^/?#]+/.test(path)
      || /\/jobs\/[^/?#]+/.test(path)
      || /jobdetail|positiondetail|job_detail|jobposition|position/.test(text);
  } catch {
    return false;
  }
}

function isBossChatUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.hostname.endsWith("zhipin.com") && /chat|im|message/.test(parsed.pathname);
  } catch {
    return false;
  }
}

function extractBossJobId(url) {
  const match = String(url || "").match(/\/job_detail\/([^/?#]+)/);
  return match ? match[1] : "";
}

function extractZhilianJobId(url) {
  try {
    const parsed = new URL(url);
    const path = parsed.pathname;
    const detailMatch = path.match(/jobdetail\/([^/?#]+?)(?:\.htm|\/|$)/i);
    if (detailMatch) return detailMatch[1];
    const pathMatch = path.match(/\/(?:job|jobs|positiondetail|job_detail)\/([^/?#]+)/i);
    if (pathMatch) return pathMatch[1].replace(/\.htm$/i, "");
    return "";
  } catch {
    const value = String(url || "");
    const detailMatch = value.match(/jobdetail\/([^/?#]+?)(?:\.htm|[/?#]|$)/i);
    if (detailMatch) return detailMatch[1];
    const pathMatch = value.match(/\/(?:job|jobs|positiondetail|job_detail)\/([^/?#]+)/i);
    return pathMatch ? pathMatch[1].replace(/\.htm$/i, "") : "";
  }
}

function buildContentScriptError(platform, error, operation = "扫描") {
  const platformName = platform === "boss" ? "Boss直聘" : platform === "zhilian" ? "智联招聘" : "招聘平台";
  const detail = error?.message || String(error || "");
  if (detail.includes("Receiving end does not exist") || detail.includes("Could not establish connection")) {
    return `${platformName}页面还没有准备好接收${operation}请求。请刷新${platformName}页面，确认扩展已重新加载后再试。`;
  }
  return `${platformName}${operation}请求发送失败：${detail}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------- Cloud 投递执行
//
// 只有 Web 用户逐条确认后网页才显式发送一次 CLOUD_DELIVERY_WAKE。这里没有
// 定时轮询循环、告警定时器或启动自动领取；浏览器或 Service Worker 重启后必须
// 等用户再次显式唤醒才恢复。同一时刻只执行一个 Cloud 投递任务：相同 taskId 的
// 重复唤醒恢复同一执行，不同 taskId 返回 PLUGIN_BUSY。

let activeCloudRun = null;

function cloudClient() {
  return globalThis.GetJobsCloudClient || null;
}

async function handleCloudDeliveryWake(message, sender) {
  const Cloud = cloudClient();
  if (!Cloud) {
    return { success: false, code: "VALIDATION_ERROR", message: "云端执行模块未加载，请重新加载扩展" };
  }
  const normalized = Cloud.normalizeWakeMessage(message);
  if (!normalized.success) {
    return { success: false, code: normalized.code, message: normalized.message };
  }
  const taskId = normalized.taskId;

  // 离线/未绑定/Token 缺失：后端任务保持 CONFIRMED，这里只报告稳定状态，
  // 不轮询、不静默重试、不替用户 skip/confirm。
  const bound = await Cloud.readBoundState();
  if (!bound) {
    return {
      success: false,
      accepted: false,
      taskId,
      state: "unbound",
      code: "PLUGIN_NOT_BOUND",
      message: "插件尚未绑定云端设备，请点击扩展图标完成绑定"
    };
  }

  if (activeCloudRun) {
    if (activeCloudRun.taskId === taskId) {
      return {
        success: true,
        accepted: true,
        taskId,
        state: "resuming",
        code: "ACCEPTED",
        message: "该任务正在执行，已恢复等待"
      };
    }
    return {
      success: false,
      accepted: false,
      taskId,
      state: "busy",
      code: "PLUGIN_BUSY",
      message: "已有其他投递任务在执行，请稍后再试"
    };
  }

  // 持久化执行状态决定恢复语义：同 taskId 复用同一 executionId/key；
  // 不同 taskId 只有在没有活动执行时才允许开始。活动判定遵循实际租约：
  // starting/reporting 绝不按本地时间自动清理，executing 以持久化
  // leaseExpiresAt（+容差）为准，时间字段畸形/缺失时保守 busy。
  const persisted = await Cloud.readExecutionState();
  if (persisted && persisted.taskId !== taskId) {
    if (Cloud.isExecutionActive(persisted)) {
      return {
        success: false,
        accepted: false,
        taskId,
        state: "busy",
        code: "PLUGIN_BUSY",
        message: "已有其他投递任务在执行，请稍后再试"
      };
    }
    // executing 租约已明确过期：保守清理后再执行当前任务，绝不并行开始第二个任务。
    await Cloud.clearExecutionState();
  }

  const pageTabId = sender.tab?.id || null;
  activeCloudRun = { taskId, startedAt: Date.now() };
  runCloudDelivery(taskId, pageTabId, bound)
    .catch(() => {})
    .finally(() => {
      if (activeCloudRun?.taskId === taskId) activeCloudRun = null;
    });
  return {
    success: true,
    accepted: true,
    taskId,
    state: persisted && persisted.taskId === taskId ? "resuming" : "accepted",
    code: "ACCEPTED",
    message: "已接收投递唤醒请求"
  };
}

async function runCloudDelivery(taskId, pageTabId, bound) {
  const Cloud = cloudClient();
  await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "accepted", "ACCEPTED", "已接收投递唤醒请求"));
  await executeCloudDelivery(taskId, pageTabId, bound);
}

async function executeCloudDelivery(taskId, pageTabId, bound) {
  const Cloud = cloudClient();

  // 已持久化报告：下一次显式唤醒原样重放 finish 请求，不重新导航/点击。
  let execution = await Cloud.readExecutionState();
  if (execution && execution.taskId === taskId && execution.phase === "reporting" && execution.report) {
    await replayCloudReport(taskId, pageTabId, bound, execution);
    return;
  }
  // 同 task 的 executing 租约已明确过期：不得再次导航/点击，清状态并报告
  // LEASE_EXPIRED，等用户下一次显式唤醒重新 pending/start。
  if (execution && execution.taskId === taskId && execution.phase === "executing" && Cloud.isLeaseExpired(execution)) {
    await Cloud.clearExecutionState();
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "LEASE_EXPIRED", "执行租约已过期，请重新确认任务后再次唤醒"));
    return;
  }

  if (!execution || execution.taskId !== taskId) {
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "fetching", "FETCHING", "正在云端领取任务"));
    const pending = await Cloud.fetchPending(bound.apiBase, bound.token);
    if (!pending.success) {
      await handleCloudApiFailure(taskId, pageTabId, bound, pending);
      return;
    }
    const item = Array.isArray(pending.data?.items)
      ? pending.data.items.find((entry) => String(entry?.id || "").toLowerCase() === taskId)
      : null;
    if (!item) {
      await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "TASK_NOT_AVAILABLE", "任务不存在或尚未确认，无法执行"));
      await Cloud.appendRecentResult({ taskId, status: "FAILED", code: "TASK_NOT_AVAILABLE", finishedAt: new Date().toISOString() });
      return;
    }
    let freshExecution;
    try {
      freshExecution = {
        taskId,
        executionId: Cloud.randomExecutionId(),
        startIdempotencyKey: Cloud.randomIdempotencyKey("start"),
        pendingVersion: Number.isInteger(Number(item.version)) ? Number(item.version) : 0,
        phase: "starting",
        updatedAt: new Date().toISOString(),
        leaseId: null,
        leaseExpiresAt: "",
        version: null,
        attemptNumber: null,
        task: null,
        report: null
      };
    } catch {
      // 安全随机不可用：不生成弱 execution/key，绝不调用 start/导航/content。
      await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "SECURE_RANDOM_UNAVAILABLE", "安全随机数不可用，无法启动投递执行"));
      return;
    }
    execution = freshExecution;
    // start 网络请求之前持久化稳定 execution/key：即使响应丢失或 Service Worker
    // 重启，重复唤醒也用原 key/payload 幂等重放同一 start。写失败则绝不调用
    // start：未持久化的幂等键无法在重启后恢复，会产生不可恢复的副作用。
    const savedStarting = await Cloud.writeExecutionState(execution);
    if (!savedStarting) {
      await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "STORAGE_UNAVAILABLE", "本地存储不可用，无法安全启动投递"));
      return;
    }
  }

  if (execution.phase === "starting") {
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "starting", "STARTING", "正在领取执行租约"));
    const startResult = await Cloud.startTask(
      bound.apiBase,
      bound.token,
      taskId,
      {
        version: execution.pendingVersion,
        executionId: execution.executionId,
        extensionVersion: Cloud.currentExtensionVersion()
      },
      execution.startIdempotencyKey
    );
    if (!startResult.success) {
      await handleCloudApiFailure(taskId, pageTabId, bound, startResult, execution);
      return;
    }
    const started = startResult.data || {};
    execution = {
      ...execution,
      phase: "executing",
      updatedAt: new Date().toISOString(),
      leaseId: started.leaseId == null ? null : String(started.leaseId),
      leaseExpiresAt: typeof started.leaseExpiresAt === "string" ? started.leaseExpiresAt : "",
      version: Number.isInteger(Number(started.version)) ? Number(started.version) : execution.pendingVersion,
      attemptNumber: Number.isInteger(Number(started.attemptNumber)) ? Number(started.attemptNumber) : null,
      // 实际导航 URL 与 Boss greeting 只采用 start 响应，不信任网页消息。
      task: {
        platform: started.task?.platform === "BOSS" ? "BOSS" : started.task?.platform === "ZHILIAN" ? "ZHILIAN" : "",
        jobUrl: String(started.task?.jobUrl || ""),
        greeting: String(started.task?.greeting || "")
      }
    };
    // start 已成功：租约/载荷必须持久化成功才能导航/点击。写失败时保留此前
    // starting 状态，下次同 task 显式唤醒用相同 start key 幂等重放。
    const savedExecuting = await Cloud.writeExecutionState(execution);
    if (!savedExecuting) {
      await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "STORAGE_UNAVAILABLE", "本地存储不可用，已停止投递执行"));
      return;
    }
  }

  if (execution.phase !== "executing" || !execution.task || !execution.task.platform) {
    await finishCloudRun(taskId, pageTabId, bound, execution, "UNKNOWN_ERROR");
    return;
  }

  await executeCloudContentDelivery(taskId, pageTabId, bound, execution);
}

async function handleCloudApiFailure(taskId, pageTabId, bound, result, execution) {
  const Cloud = cloudClient();
  if (result.code && Cloud.shouldClearTokenOnCode(result.code)) {
    // 401/403 稳定错误码：清理本机 Token 并停止，不自动重试。
    await Cloud.clearBoundState();
    await Cloud.clearExecutionState();
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", result.code, "云端凭证已失效，请重新绑定"));
    return;
  }
  if (Cloud.isRetryableCloudFailure(result)) {
    // 429/5xx/网络错误：保留已持久化的 execution（含 startIdempotencyKey），
    // 报告 offline，等用户再次显式唤醒后重放同一 start。
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "offline", result.code || "NETWORK_ERROR", "云端服务暂不可用，请稍后再次唤醒"));
    return;
  }
  // 409 幂等冲突/版本变化/租约问题：不能无界自动重试，清理后只报告稳定状态。
  await Cloud.clearExecutionState();
  await postCloudDeliveryEvent(
    pageTabId,
    Cloud.cloudEvent(taskId, "failed", result.code || "INVALID_STATE_TRANSITION", result.message || "任务状态不允许执行")
  );
}

async function executeCloudContentDelivery(taskId, pageTabId, bound, execution) {
  const Cloud = cloudClient();
  const platform = execution.task.platform;
  const config = PLATFORM_CONFIG[platform === "ZHILIAN" ? "zhilian" : "boss"];

  // 导航前再次严格校验 HTTPS + 正确 host label + 岗位详情 path，
  // 拒绝 lookalike、首页、搜索页、端口、userinfo、query/fragment 与编码绕过。
  const jobUrl = Cloud.isTrustedJobUrl(execution.task.jobUrl, platform);
  if (!jobUrl) {
    await finishCloudRun(taskId, pageTabId, bound, execution, "PAGE_CHANGED");
    return;
  }

  let tab;
  try {
    tab = await findOrCreatePlatformTab(platform === "ZHILIAN" ? "zhilian" : "boss", jobUrl, { active: true });
    if (!tab?.id) throw new Error("缺少平台标签页");
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "navigating", "NAVIGATING", "正在打开岗位详情页"));
    await navigatePlatformTab(tab.id, jobUrl, config, DELIVERY_NAVIGATION_TIMEOUT_MS, platform === "BOSS" ? { bossJobUrl: jobUrl } : { zhilianJobUrl: jobUrl });
    await ensureContentScript(tab.id, config.contentScript);
  } catch {
    await finishCloudRun(taskId, pageTabId, bound, execution, "NETWORK_ERROR");
    return;
  }

  // Token、lease、executionId 一律不传入招聘平台 content script；
  // content 只收到受控的 url/id/greeting 并回传固定枚举结果。
  await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "executing", "EXECUTING", "正在页面执行投递"));
  const contentTask = {
    url: jobUrl,
    id: taskId
  };
  if (platform === "BOSS") {
    contentTask.greeting = String(execution.task.greeting || "");
  }
  const messageType = platform === "BOSS" ? "BOSS_DELIVER_CURRENT_V2" : "ZHILIAN_DELIVER_CURRENT_V2";
  let contentResult = null;
  try {
    contentResult = await chrome.tabs.sendMessage(tab.id, {
      type: messageType,
      source: "GET_JOBS_BACKGROUND",
      cloudManaged: true,
      task: contentTask,
      pageTabId,
      deliveryIndex: 1,
      deliveryTotal: 1
    });
  } catch {
    contentResult = null;
  }

  const normalized = Cloud.normalizeContentResult(contentResult, platform);
  if (!normalized.valid) {
    await finishCloudRun(taskId, pageTabId, bound, execution, "UNKNOWN_ERROR");
    return;
  }
  if (normalized.kind === "success") {
    const resultCode = contentResult?.resultCode === "ALREADY_DELIVERED" ? "ALREADY_DELIVERED" : "DELIVERED";
    await finishCloudRun(taskId, pageTabId, bound, execution, resultCode);
    return;
  }
  await finishCloudRun(taskId, pageTabId, bound, execution, normalized.code);
}

/**
 * 内容结果规范为后端契约并回传。finish 请求前先持久化已规范化报告：
 * 网络失败时保留，下一次显式唤醒原样重放；success/fail/pause 成功后清理
 * 活动执行状态，只保留不敏感的最近结果。reporting 写失败时绝不发送
 * finish：保留 executing 状态，等下一次同 task 显式唤醒重新检查页面并
 * 形成结果（Boss 已沟通/智联已投递由 content 判定，避免重复平台动作）。
 */
async function finishCloudRun(taskId, pageTabId, bound, execution, code) {
  const Cloud = cloudClient();
  const report = Cloud.buildReportPayload(execution, code, new Date().toISOString());
  let reportIdempotencyKey;
  try {
    reportIdempotencyKey = Cloud.randomIdempotencyKey("report");
  } catch {
    // 安全随机不可用：不发送未经持久化的 finish，保留 executing 状态。
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "SECURE_RANDOM_UNAVAILABLE", "安全随机数不可用，无法回传执行结果"));
    return;
  }
  const persisted = {
    ...execution,
    phase: "reporting",
    updatedAt: new Date().toISOString(),
    report: {
      kind: report.kind,
      payload: report.payload,
      reportIdempotencyKey
    }
  };
  const savedReport = await Cloud.writeExecutionState(persisted);
  if (!savedReport) {
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", "STORAGE_UNAVAILABLE", "本地存储不可用，执行结果未能安全保存"));
    return;
  }
  await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "reporting", "REPORTING", "正在回传执行结果"));
  await sendCloudReport(taskId, pageTabId, bound, persisted);
}

async function replayCloudReport(taskId, pageTabId, bound, execution) {
  const Cloud = cloudClient();
  await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "reporting", "REPORTING", "正在重放上次执行结果"));
  await sendCloudReport(taskId, pageTabId, bound, execution);
}

async function sendCloudReport(taskId, pageTabId, bound, execution) {
  const Cloud = cloudClient();
  const report = execution.report;
  const apiCall = report.kind === "success"
    ? Cloud.reportSuccess
    : report.kind === "pause"
      ? Cloud.reportPause
      : Cloud.reportFail;
  const result = await apiCall(bound.apiBase, bound.token, taskId, report.payload, report.reportIdempotencyKey);

  if (result.success) {
    await Cloud.clearExecutionState();
    const code = report.kind === "pause"
      ? String(report.payload?.reason || "")
      : report.kind === "success"
        ? String(report.payload?.resultCode || "")
        : String(report.payload?.errorCode || "");
    const status = report.kind === "pause" ? "PAUSED" : report.kind === "success" ? "SUCCEEDED" : "FAILED";
    const stage = report.kind === "pause" ? "paused" : report.kind === "success" ? "succeeded" : "failed";
    await Cloud.appendRecentResult({ taskId, status, code, finishedAt: new Date().toISOString() });
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, stage, code, cloudReportMessage(report)));
    return;
  }
  if (result.code && Cloud.shouldClearTokenOnCode(result.code)) {
    await Cloud.clearBoundState();
    await Cloud.clearExecutionState();
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "failed", result.code, "云端凭证已失效，请重新绑定"));
    return;
  }
  if (Cloud.isRetryableCloudFailure(result)) {
    // 保留已持久化 report，等用户再次显式唤醒后原样重放。
    await postCloudDeliveryEvent(pageTabId, Cloud.cloudEvent(taskId, "offline", result.code || "NETWORK_ERROR", "结果回传失败，请稍后再次唤醒"));
    return;
  }
  // 409 租约/版本问题：不能无界重试，清理后只报告稳定状态。
  await Cloud.clearExecutionState();
  await postCloudDeliveryEvent(
    pageTabId,
    Cloud.cloudEvent(taskId, "failed", result.code || "REPORT_REJECTED", "执行结果未能被云端接受")
  );
}

function cloudReportMessage(report) {
  const message = report?.payload?.message;
  if (typeof message === "string" && message) return message;
  if (report?.kind === "pause") return "投递已暂停，请在网页查看原因";
  if (report?.kind === "success") return "投递执行成功";
  return "投递执行失败";
}

/** 页面进度事件：payload 只含 taskId + 稳定 stage/code/message/time。 */
async function postCloudDeliveryEvent(pageTabId, payload) {
  if (pageTabId) {
    try {
      const tab = await chrome.tabs.get(pageTabId);
      if (isAllowedPageUrl(tab.url || tab.pendingUrl || "")) {
        await chrome.tabs.sendMessage(pageTabId, {
          source: "GET_JOBS_BACKGROUND",
          type: "GET_JOBS_EXTENSION_EVENT",
          payload
        }).catch(() => {});
        return;
      }
    } catch {
      // 页面标签页已关闭时退回广播。
    }
  }
  await broadcastPlatformEvent({ ...payload, platform: "cloud" }, pageTabId);
}
