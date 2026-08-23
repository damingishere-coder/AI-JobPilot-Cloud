const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { webcrypto } = require("node:crypto");

const EXTENSION_DIR = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(EXTENSION_DIR, "..");

const EXTENSION_ID = "ompipmnadogogfbebnmjgbbcadildpbc";
const EXTENSION_ORIGIN = `chrome-extension://${EXTENSION_ID}`;
const TEST_TASK_ID = "6b6f6c1e-8d3a-4c7a-9f2b-0d1e2f3a4b5c";
const OTHER_TASK_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0001";
const API_BASE = "http://localhost:8080";
const TOKEN_SENTINEL = "ajp_plg_token_sentinel_9f2a";
const GREETING_SENTINEL = "GREETING_SENTINEL_xyz";
const LEASE_SENTINEL = "lease-sentinel-123";

function read(relativePath) {
  return fs.readFileSync(path.join(EXTENSION_DIR, relativePath), "utf8");
}

function readContentVersion(file) {
  const match = read(file).match(/const EXTENSION_VERSION = "([^"]+)"/);
  assert.ok(match, `missing EXTENSION_VERSION in ${file}`);
  return match[1];
}

const BOSS_CONTENT_VERSION = readContentVersion("boss-content.js");
const ZHILIAN_CONTENT_VERSION = readContentVersion("zhilian-content.js");

function makeConsoleSpy(logs) {
  return {
    log(...args) { logs.push(["log", ...args.map((item) => safeString(item))]); },
    warn(...args) { logs.push(["warn", ...args.map((item) => safeString(item))]); },
    error(...args) { logs.push(["error", ...args.map((item) => safeString(item))]); },
    info(...args) { logs.push(["info", ...args.map((item) => safeString(item))]); },
    debug(...args) { logs.push(["debug", ...args.map((item) => safeString(item))]); }
  };
}

function safeString(value) {
  try {
    return typeof value === "string" ? value : JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function waitUntil(predicate, timeoutMs = 3000) {
  const startedAt = Date.now();
  return new Promise((resolve) => {
    const tick = async () => {
      if (predicate()) return resolve(true);
      if (Date.now() - startedAt > timeoutMs) return resolve(false);
      setTimeout(tick, 10);
    };
    tick();
  });
}

const BOSS_JOB_URL = "https://www.zhipin.com/job_detail/abc123.html";
const ZHILIAN_JOB_URL = "https://www.zhaopin.com/jobdetail/demo.htm";

function defaultPendingItem(platform = "BOSS") {
  return {
    id: TEST_TASK_ID,
    version: 3,
    platform,
    jobUrl: platform === "BOSS" ? BOSS_JOB_URL : ZHILIAN_JOB_URL,
    externalJobId: "ext-1",
    title: "Java工程师",
    companyName: "示例公司",
    greeting: GREETING_SENTINEL,
    confirmedAt: "2026-08-12T08:00:00Z",
    confirmationVersion: 1
  };
}

function defaultStartData(platform = "BOSS") {
  return {
    id: TEST_TASK_ID,
    status: "RUNNING",
    leaseId: LEASE_SENTINEL,
    leaseExpiresAt: "2026-08-13T10:00:00Z",
    version: 4,
    attemptNumber: 1,
    task: {
      platform,
      jobUrl: platform === "BOSS" ? BOSS_JOB_URL : ZHILIAN_JOB_URL,
      greeting: GREETING_SENTINEL
    }
  };
}

function envelope(data) {
  return { success: true, data, requestId: "req_test" };
}

function errorEnvelope(status, code, message = "请求失败") {
  return { success: false, error: { code, message, retryable: false }, requestId: "req_test" };
}

/**
 * 在 VM 中加载 cloud-client.js + background.js，返回调度入口与可观察对象。
 * options.storage 可传入以跨“Service Worker 重启”共享持久化状态。
 */
function loadCloudBackground(options = {}) {
  const storage = options.storage || {};
  if (options.bound !== undefined) {
    if (options.bound === null) delete storage["__GET_JOBS_CLOUD_BOUND__"];
    else storage["__GET_JOBS_CLOUD_BOUND__"] = options.bound;
  }
  const tabList = (options.tabs || [
    { id: 20, windowId: 1, url: "http://localhost:6866/delivery", status: "complete" },
    { id: 1, windowId: 1, url: "https://www.zhipin.com/web/geek/job", status: "complete" }
  ]).map((tab) => ({ ...tab }));
  const sentMessages = [];
  const executedScripts = [];
  const fetchCalls = [];
  const consoleLogs = [];
  const syncCalls = [];
  let runtimeMessageListener = null;

  const chrome = {
    runtime: {
      onMessage: { addListener(listener) { runtimeMessageListener = listener; } },
      lastError: null,
      getManifest: () => ({ version: "1.4.0" })
    },
    tabs: {
      onRemoved: { addListener() {} },
      async query() { return tabList.map((tab) => ({ ...tab })); },
      async get(tabId) {
        const tab = tabList.find((item) => item.id === tabId);
        if (!tab) throw new Error(`unknown tab ${tabId}`);
        return { ...tab };
      },
      async create(createOptions) {
        const tab = {
          id: Math.max(0, ...tabList.map((item) => item.id)) + 1,
          windowId: 1,
          status: "complete",
          lastAccessed: Date.now(),
          ...createOptions
        };
        tabList.push(tab);
        return { ...tab };
      },
      async update(tabId, updates) {
        const tab = tabList.find((item) => item.id === tabId);
        Object.assign(tab, updates);
        return { ...tab };
      },
      async sendMessage(tabId, message) {
        sentMessages.push({ tabId, message });
        if (message.type === "PING_CONTENT") return { success: true };
        if (message.type === "GET_BOSS_CONTENT_VERSION") return { success: true, version: BOSS_CONTENT_VERSION };
        if (message.type === "GET_ZHILIAN_CONTENT_VERSION") return { success: true, version: ZHILIAN_CONTENT_VERSION };
        if (message.type === "BOSS_DELIVER_CURRENT_V2" || message.type === "ZHILIAN_DELIVER_CURRENT_V2") {
          if (message.cloudManaged) {
            return options.contentResult || { success: true, resultCode: "DELIVERED", pageState: "SUCCESS_NOTICE", message: "模拟投递成功" };
          }
          return { success: true };
        }
        if (message.type === "BOSS_SCAN_STATUS" || message.type === "ZHILIAN_SCAN_STATUS_V2") {
          return { success: true, isRunning: false, hasStoredTask: false, stage: "idle" };
        }
        return { success: true };
      }
    },
    windows: { async update() {} },
    scripting: {
      async executeScript(scriptOptions) {
        executedScripts.push(scriptOptions);
        return [];
      }
    },
    storage: {
      local: {
        async get(key) { return { [key]: storage[key] }; },
        async set(values) {
          // 故障注入：精确在指定 phase 的执行状态写点失败（模拟 MV3 存储不可用）。
          if (Array.isArray(options.failExecutionSetPhases) && options.failExecutionSetPhases.length) {
            const state = values["__GET_JOBS_CLOUD_EXECUTION__"];
            if (state && options.failExecutionSetPhases.includes(state.phase)) {
              throw new Error("injected storage.local.set failure");
            }
          }
          Object.assign(storage, values);
        },
        async remove(key) {
          for (const item of Array.isArray(key) ? key : [key]) delete storage[item];
        }
      },
      sync: {
        async get() { syncCalls.push("get"); throw new Error("chrome.storage.sync must never be used"); },
        async set() { syncCalls.push("set"); throw new Error("chrome.storage.sync must never be used"); },
        async remove() { syncCalls.push("remove"); throw new Error("chrome.storage.sync must never be used"); }
      }
    }
  };

  const fetchSpy = async (url, fetchOptions) => {
    const call = { url: String(url), options: fetchOptions || {}, method: fetchOptions?.method || "GET" };
    fetchCalls.push(call);
    if (options.fetchImpl) {
      const response = await options.fetchImpl(call, { storage, sentMessages, fetchCalls });
      if (response && typeof response.ok !== "undefined") return response;
      if (response instanceof Error) throw response;
      throw response;
    }
    return {
      ok: true,
      status: 200,
      async text() { return JSON.stringify(envelope({})); }
    };
  };

  // VM 内的 sleep/轮询使用 unref 计时器：测试结束后残留的执行队列轮询
  // 不得阻止 node --test 进程退出（真实 MV3 service worker 由浏览器回收）。
  const unrefSetTimeout = (callback, delay, ...args) => {
    const timer = setTimeout(callback, delay, ...args);
    if (timer && typeof timer.unref === "function") timer.unref();
    return timer;
  };

  const context = vm.createContext({
    chrome,
    console: makeConsoleSpy(consoleLogs),
    URL,
    URLSearchParams,
    AbortController,
    fetch: fetchSpy,
    setTimeout: unrefSetTimeout,
    clearTimeout,
    crypto: options.crypto === undefined ? webcrypto : options.crypto,
    btoa: (value) => Buffer.from(value, "binary").toString("base64"),
    atob: (value) => Buffer.from(value, "base64").toString("binary")
  });
  vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
  vm.runInContext(read("background.js"), context, { filename: "background.js" });

  async function dispatchRuntimeMessage(message, sender) {
    return await new Promise((resolve) => {
      const keepChannelOpen = runtimeMessageListener(message, sender, resolve);
      if (keepChannelOpen !== true) setImmediate(() => resolve(undefined));
    });
  }

  function wake(taskId, requestId = "req-1", sender) {
    return dispatchRuntimeMessage({
      source: "GET_JOBS_PAGE",
      type: "CLOUD_DELIVERY_WAKE",
      taskId,
      requestId
    }, sender || { tab: { id: 20, url: "http://localhost:6866/delivery" } });
  }

  return {
    context,
    storage,
    tabList,
    sentMessages,
    executedScripts,
    fetchCalls,
    consoleLogs,
    syncCalls,
    runtimeMessageListener,
    dispatchRuntimeMessage,
    wake
  };
}

function boundState(overrides = {}) {
  return {
    apiBase: API_BASE,
    installationId: "install-sentinel-abcdefgh",
    token: TOKEN_SENTINEL,
    tokenExpiresAt: "2026-09-01T00:00:00Z",
    device: {
      id: "device-1",
      deviceName: "测试设备",
      browserName: "Chrome",
      browserVersion: "120",
      extensionVersion: "1.4.0",
      status: "ACTIVE",
      capabilities: ["BOSS", "ZHILIAN"],
      boundAt: "2026-08-01T00:00:00Z"
    },
    ...overrides
  };
}

/** 标准成功链路 fetch 模拟：pending → start → finish 全部成功。 */
function fetchSuccessChain(overrides = {}) {
  const platform = overrides.platform || "BOSS";
  return async (call) => {
    const url = call.url;
    if (url.includes("/api/plugin/tasks/pending")) {
      return jsonResponse(envelope({
        items: [
          defaultPendingItem(platform),
          { ...defaultPendingItem(platform), id: OTHER_TASK_ID, externalJobId: "ext-2" }
        ],
        pollAfterSeconds: 10,
        serverTime: new Date().toISOString()
      }));
    }
    if (/\/api\/plugin\/tasks\/[^/]+\/start$/.test(url)) {
      return jsonResponse(envelope(defaultStartData(platform)));
    }
    if (/\/api\/plugin\/tasks\/[^/]+\/(success|fail|pause)$/.test(url)) {
      return jsonResponse(envelope({
        id: TEST_TASK_ID,
        status: /success/.test(url) ? "SUCCESS" : /pause/.test(url) ? "PAUSED_NEED_USER" : "FAILED",
        finishedAt: new Date().toISOString(),
        version: 5
      }));
    }
    return jsonResponse(envelope({}));
  };
}

function jsonResponse(payload, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async text() { return JSON.stringify(payload); }
  };
}

/** 在 VM 中加载 page-bridge.js（注入 6866 页面）。 */
function loadPageBridge(pageOrigin = "http://localhost:6866") {
  const posted = [];
  const runtimeMessages = [];
  const windowListeners = [];
  let runtimeListener = null;
  const windowMock = {
    location: { origin: pageOrigin },
    postMessage(payload, targetOrigin) { posted.push({ payload, targetOrigin }); },
    addEventListener(type, listener) {
      if (type === "message") windowListeners.push(listener);
    }
  };
  const chromeMock = {
    runtime: {
      lastError: null,
      sendMessage(message, callback) {
        runtimeMessages.push(message);
        callback?.({ success: true, accepted: true, taskId: message.taskId });
      },
      onMessage: { addListener(listener) { runtimeListener = listener; } }
    }
  };
  const context = vm.createContext({ window: windowMock, chrome: chromeMock, console: makeConsoleSpy([]), URL });
  vm.runInContext(read("page-bridge.js"), context, { filename: "page-bridge.js" });
  function dispatchWindowMessage(event) {
    for (const listener of windowListeners) listener(event);
  }
  return { posted, runtimeMessages, dispatchWindowMessage, runtimeListener, context };
}

/** 最小 DOM mock 加载 popup.js。 */
function loadPopup(options = {}) {
  const storage = options.storage || {};
  const fetchCalls = [];
  const syncCalls = [];
  const permissionChecks = [];
  const permissionRequests = [];
  const elements = new Map();

  function makeElement(id) {
    const element = {
      id,
      value: "",
      textContent: "",
      className: "",
      disabled: false,
      innerHTML: "",
      children: [],
      listeners: {},
      classList: {
        add(name) { element.className = [element.className, name].filter(Boolean).join(" "); },
        remove(name) { element.className = element.className.split(" ").filter((item) => item !== name).join(" "); },
        toggle(name, force) {
          const has = element.className.split(" ").includes(name);
          const shouldHave = force === undefined ? !has : Boolean(force);
          if (shouldHave && !has) element.className = [element.className, name].filter(Boolean).join(" ");
          if (!shouldHave && has) element.className = element.className.split(" ").filter((item) => item !== name).join(" ");
          return shouldHave;
        }
      },
      appendChild(child) { element.children.push(child); },
      addEventListener(type, listener) { element.listeners[type] = listener; }
    };
    elements.set(id, element);
    return element;
  }

  const ids = [
    "bind-section", "status-section", "api-base-select", "api-base-custom-field", "api-base-input",
    "api-base-display", "bind-code-input", "device-name-input",
    "bind-button", "unbind-button", "capture-current-button", "capture-batch-button", "capture-queue-count", "status-dot", "status-text", "device-name", "device-browser",
    "device-extension", "token-expires", "message",
    "execution-dot", "execution-status-text", "execution-failures", "execution-summary",
    "execution-start-button", "execution-pause-button"
  ];
  for (const id of ids) makeElement(id);

  const documentMock = {
    getElementById: (id) => elements.get(id) || null,
    createElement: (tag) => makeElement(`created-${tag}-${Math.random().toString(16).slice(2)}`)
  };

  const chromeMock = {
    runtime: { getManifest: () => ({ version: "1.5.0" }) },
    storage: {
      local: {
        async get(key) { return { [key]: storage[key] }; },
        async set(values) { Object.assign(storage, values); },
        async remove(key) {
          for (const item of Array.isArray(key) ? key : [key]) delete storage[item];
        }
      },
      sync: {
        async get() { syncCalls.push("get"); throw new Error("sync must never be used"); },
        async set() { syncCalls.push("set"); throw new Error("sync must never be used"); }
      }
    },
    permissions: options.permissionsImpl || {
      // 默认视为“尚未授权”（contains=false），从而走 request 路径，让测试
      // 能观察到精确的 origins 请求；request 默认授权，可用 permissionDenied
      // 模拟用户拒绝（fail-closed）。origins 复制进 host realm，避免 VM
      // realm 数组原型导致 deepStrictEqual 跨 realm 失败。
      async contains(request = {}) {
        const origins = Array.isArray(request.origins) ? [...request.origins] : [];
        permissionChecks.push({ origins });
        return options.permissionGranted === true;
      },
      async request(request = {}) {
        const origins = Array.isArray(request.origins) ? [...request.origins] : [];
        permissionRequests.push({ origins });
        if (options.permissionDenied) return false;
        return options.permissionGranted !== false;
      }
    }
  };

  const context = vm.createContext({
    document: documentMock,
    chrome: chromeMock,
    navigator: { userAgent: "Mozilla/5.0 Chrome/120.0" },
    console: makeConsoleSpy([]),
    fetch: async (url, fetchOptions) => {
      const call = { url: String(url), options: fetchOptions || {}, method: fetchOptions?.method || "GET" };
      fetchCalls.push(call);
      if (options.fetchImpl) {
        const response = await options.fetchImpl(call);
        if (response instanceof Error) throw response;
        return response;
      }
      return jsonResponse(envelope({}));
    },
    setTimeout,
    clearTimeout,
    crypto: options.crypto === undefined ? webcrypto : options.crypto,
    btoa: (value) => Buffer.from(value, "binary").toString("base64"),
    atob: (value) => Buffer.from(value, "base64").toString("binary"),
    AbortController,
    Date,
    URL
  });
  vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
  vm.runInContext(read("popup.js"), context, { filename: "popup.js" });

  return { elements, storage, fetchCalls, syncCalls, permissionChecks, permissionRequests, context };
}

module.exports = {
  EXTENSION_DIR,
  REPO_ROOT,
  EXTENSION_ID,
  EXTENSION_ORIGIN,
  TEST_TASK_ID,
  OTHER_TASK_ID,
  API_BASE,
  TOKEN_SENTINEL,
  GREETING_SENTINEL,
  LEASE_SENTINEL,
  BOSS_JOB_URL,
  ZHILIAN_JOB_URL,
  read,
  waitUntil,
  loadCloudBackground,
  loadPageBridge,
  loadPopup,
  boundState,
  fetchSuccessChain,
  jsonResponse,
  envelope,
  errorEnvelope,
  defaultPendingItem,
  defaultStartData,
  safeString
};
