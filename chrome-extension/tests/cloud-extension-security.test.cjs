const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { createHash } = require("node:crypto");

const harness = require("./cloud-test-harness.cjs");
const {
  EXTENSION_DIR,
  REPO_ROOT,
  EXTENSION_ID,
  EXTENSION_ORIGIN,
  TEST_TASK_ID,
  API_BASE,
  TOKEN_SENTINEL,
  GREETING_SENTINEL,
  LEASE_SENTINEL,
  read,
  waitUntil,
  loadPageBridge,
  loadPopup,
  boundState,
  jsonResponse,
  envelope,
  errorEnvelope
} = harness;

function deriveExtensionId(publicKeyBase64) {
  const der = Buffer.from(publicKeyBase64, "base64");
  const digest = createHash("sha256").update(der).digest();
  return Array.from(digest.subarray(0, 16))
    .map((byte) => String.fromCharCode(97 + (byte >> 4), 97 + (byte & 15)))
    .join("");
}

function functionBody(source, functionName) {
  const start = source.search(new RegExp(`(?:async )?function ${functionName}\\s*\\(`));
  assert.ok(start >= 0, `missing function ${functionName}`);
  const rest = source.slice(start);
  const match = rest.match(/\{\n/);
  assert.ok(match, `no body for ${functionName}`);
  const bodyStart = start + match.index + match[0].length;
  let depth = 1;
  let index = bodyStart;
  while (depth > 0 && index < source.length) {
    const ch = source[index];
    if (ch === "{") depth += 1;
    else if (ch === "}") depth -= 1;
    index += 1;
  }
  return source.slice(bodyStart, index - 1);
}

// ---------------------------------------------------------------- 1. manifest

test("manifest is MV3 1.4.0 with a fixed public key deriving the expected extension ID", () => {
  const manifest = JSON.parse(read("manifest.json"));
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.version, "1.4.0");
  assert.ok(manifest.key && manifest.key.length > 100, "manifest.key 必须存在且为固定公钥");
  assert.equal(deriveExtensionId(manifest.key), EXTENSION_ID);
  assert.equal(manifest.action?.default_popup, "popup.html");
  assert.equal(manifest.background?.service_worker, "background.js");
});

test("manifest permissions stay minimal and host permissions match the approved entries", () => {
  const manifest = JSON.parse(read("manifest.json"));
  const permissions = manifest.permissions || [];
  for (const forbidden of ["cookies", "history", "alarms", "clipboard", "webRequest", "webRequestBlocking", "declarativeNetRequest"]) {
    assert.ok(!permissions.includes(forbidden), `禁止权限 ${forbidden}`);
  }
  const hostPermissions = manifest.host_permissions || [];
  const joined = JSON.stringify(hostPermissions);
  assert.ok(!joined.includes("<all_urls>"), "host_permissions 不得包含 <all_urls>");
  for (const host of ["http://localhost:6866/*", "http://127.0.0.1:6866/*", "http://localhost:8080/*", "http://127.0.0.1:8080/*", "http://localhost:8888/*", "http://127.0.0.1:8888/*"]) {
    assert.ok(hostPermissions.includes(host), `host_permissions 需要 ${host}`);
  }
  const bridgeScript = manifest.content_scripts.find((entry) => entry.js?.includes("page-bridge.js"));
  assert.ok(bridgeScript, "page-bridge content script 存在");
  for (const match of bridgeScript.matches) {
    assert.ok(/^http:\/\/(localhost|127\.0\.0\.1):(6866|8080)\/\*$/.test(match), `bridge match 越界：${match}`);
  }
  for (const file of ["popup.html", "popup.js", "popup.css", "cloud-client.js"]) {
    assert.ok(fs.existsSync(path.join(EXTENSION_DIR, file)), `缺少文件 ${file}`);
  }
});

test("backend CORS default origin matches the manifest-derived extension ID", () => {
  const apiYaml = fs.readFileSync(path.join(REPO_ROOT, "src/main/resources/application-api.yaml"), "utf8");
  assert.ok(apiYaml.includes(`PLUGIN_ALLOWED_EXTENSION_ORIGINS:${EXTENSION_ORIGIN}`), "application-api.yaml 默认 origin 必须等于派生扩展 ID");
  const envExample = fs.readFileSync(path.join(REPO_ROOT, ".env.example"), "utf8");
  assert.ok(envExample.includes(`PLUGIN_ALLOWED_EXTENSION_ORIGINS=${EXTENSION_ORIGIN}`), ".env.example 必须只含公开精确 origin");
  const compose = fs.readFileSync(path.join(REPO_ROOT, "docker-compose.yml"), "utf8");
  assert.ok(compose.includes(`PLUGIN_ALLOWED_EXTENSION_ORIGINS:-${EXTENSION_ORIGIN}`), "docker-compose.yml 必须透传精确 origin");
  assert.ok(read("cloud-client.js").includes(`const EXTENSION_ORIGIN = "${EXTENSION_ORIGIN}"`));
});

// ---------------------------------------------------------------- 2. page bridge

test("page bridge relays only valid wake messages from allowed page origins", () => {
  const bridge = loadPageBridge("http://localhost:6866");
  const windowRef = bridge.context.window;
  const valid = {
    source: "GET_JOBS_PAGE",
    type: "CLOUD_DELIVERY_WAKE",
    taskId: TEST_TASK_ID,
    requestId: "req-abc"
  };

  // event.source 必须等于 window：source 不是 window 的合法消息被拒绝。
  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: {}, data: valid });
  bridge.dispatchWindowMessage({ origin: "http://localhost:9999", source: windowRef, data: valid });
  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: { ...valid, source: "GET_JOBS_FAKE" } });
  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: { ...valid, type: "EVIL_TYPE" } });
  assert.equal(bridge.runtimeMessages.length, 0, "错误 origin/source/type 不得转发");

  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: valid });
  assert.equal(bridge.runtimeMessages.length, 1);
  assert.equal(bridge.runtimeMessages[0].type, "CLOUD_DELIVERY_WAKE");
});

test("page bridge accepts wake from the approved 8080 origin", () => {
  const bridge = loadPageBridge("http://localhost:8080");
  const valid = {
    source: "GET_JOBS_PAGE",
    type: "CLOUD_DELIVERY_WAKE",
    taskId: TEST_TASK_ID,
    requestId: "req-1"
  };
  const windowRef = bridge.context.window;
  bridge.dispatchWindowMessage({ origin: "http://localhost:8080", source: windowRef, data: valid });
  assert.equal(bridge.runtimeMessages.length, 1);
  assert.equal(bridge.runtimeMessages[0].type, "CLOUD_DELIVERY_WAKE");
  assert.deepEqual(Object.keys(bridge.runtimeMessages[0]).sort(), ["requestId", "source", "taskId", "type"]);
});

test("page bridge rejects malformed cloud wakes before the extension backend is called", () => {
  const bridge = loadPageBridge("http://localhost:6866");
  const windowRef = bridge.context.window;
  const base = { source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "req-1" };

  // 非 UUID taskId、空/超长/非法字符 requestId：一律不调用 runtime。
  for (const bad of [
    { ...base, taskId: "not-a-uuid" },
    { ...base, taskId: "6b6f6c1e8d3a4c7a9f2b0d1e2f3a4b5c" },
    { ...base, requestId: "" },
    { ...base, requestId: " " },
    { ...base, requestId: "x".repeat(129) },
    { ...base, requestId: "bad id" },
    { ...base, requestId: "req<script>" },
    { ...base, requestId: "req\r\nx" }
  ]) {
    bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: bad });
  }
  assert.equal(bridge.runtimeMessages.length, 0, "畸形唤醒不得转发进扩展后台");

  // 合法消息继续转发。
  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: base });
  assert.equal(bridge.runtimeMessages.length, 1);
});

test("page bridge forwards only the four allowed fields and drops sensitive extras", () => {
  const bridge = loadPageBridge("http://localhost:6866");
  const windowRef = bridge.context.window;
  const message = {
    source: "GET_JOBS_PAGE",
    type: "CLOUD_DELIVERY_WAKE",
    taskId: TEST_TASK_ID,
    requestId: "req-extra",
    token: TOKEN_SENTINEL,
    url: "https://www.zhipin.com/job_detail/x.html",
    greeting: GREETING_SENTINEL,
    lease: LEASE_SENTINEL,
    executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
    payload: { nested: "unbounded-data" }
  };
  bridge.dispatchWindowMessage({ origin: "http://localhost:6866", source: windowRef, data: message });
  assert.equal(bridge.runtimeMessages.length, 1, "合法但带额外字段的唤醒应转发");
  assert.deepEqual(Object.keys(bridge.runtimeMessages[0]).sort(), ["requestId", "source", "taskId", "type"]);
  assert.equal(bridge.runtimeMessages[0].taskId, TEST_TASK_ID);
  assert.deepEqual(
    Object.values(bridge.runtimeMessages[0]).join(" "),
    ["GET_JOBS_PAGE", "CLOUD_DELIVERY_WAKE", TEST_TASK_ID, "req-extra"].join(" "),
    "转发的值必须与四个允许字段一致"
  );
  // 转发出去的消息里绝不含任何敏感字段。
  const forwarded = JSON.stringify(bridge.runtimeMessages[0]);
  assert.ok(!forwarded.includes(TOKEN_SENTINEL));
  assert.ok(!forwarded.includes(GREETING_SENTINEL));
  assert.ok(!forwarded.includes(LEASE_SENTINEL));
  assert.ok(!forwarded.includes("executionId"));
});

test("page bridge cloud wake fallback only carries stable success/code/message", () => {
  const bridge = loadPageBridge("http://localhost:6866");
  const windowRef = bridge.context.window;
  bridge.context.chrome.runtime.lastError = { message: "Receiving end does not exist" };
  bridge.context.chrome.runtime.sendMessage = (message, callback) => {
    callback?.(null);
  };
  bridge.dispatchWindowMessage({
    origin: "http://localhost:6866",
    source: windowRef,
    data: { source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "req-1" }
  });
  const wakeResponses = bridge.posted.filter((entry) => entry.payload.type === "CLOUD_DELIVERY_WAKE_RESPONSE");
  assert.equal(wakeResponses.length, 1);
  const response = wakeResponses[0].payload.response;
  assert.equal(response.success, false);
  assert.ok(["EXTENSION_UNAVAILABLE"].includes(response.code), "稳定错误码");
  assert.ok(typeof response.message === "string" && response.message.length > 0);
  assert.ok(!Object.prototype.hasOwnProperty.call(response, "rawMessage"), "Cloud 唤醒不得透出 rawMessage");
  assert.deepEqual(Object.keys(response).sort(), ["code", "message", "success"]);
});

// ---------------------------------------------------------------- 3. cloud-client units

test("cloud client URL trust rejects lookalikes, search pages, ports, userinfo, query and fragments", () => {
  const run = (url, platform) => {
    const context = vm.createContext({ URL, chrome: undefined, console, crypto: require("node:crypto").webcrypto, btoa: (v) => Buffer.from(v, "binary").toString("base64") });
    vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
    return context.GetJobsCloudClient.isTrustedJobUrl(url, platform);
  };

  assert.equal(run("https://www.zhipin.com/job_detail/abc123.html", "BOSS"), "https://www.zhipin.com/job_detail/abc123.html");
  assert.equal(run("https://www.zhipin.com/web/geek/job_detail/abc123.html", "BOSS"), "https://www.zhipin.com/web/geek/job_detail/abc123.html");
  assert.equal(run("https://www.zhipin.com/job_detail/abc123.html?ka=track", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com/job_detail/abc123.html#frag", "BOSS"), "");
  assert.equal(run("http://www.zhipin.com/job_detail/abc123.html", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com/web/geek/job", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com/", "BOSS"), "");
  assert.equal(run("https://zhipin.com.evil.com/job_detail/abc123.html", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com:8443/job_detail/abc123.html", "BOSS"), "");
  assert.equal(run("https://user@www.zhipin.com/job_detail/abc123.html", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com/job_detail/../job_detail/abc123.html", "BOSS"), "");
  assert.equal(run("https://www.zhipin.com/job_detail/abc%2ehtml", "BOSS"), "");

  assert.equal(run("https://www.zhaopin.com/jobdetail/demo.htm", "ZHILIAN"), "https://www.zhaopin.com/jobdetail/demo.htm");
  assert.equal(run("https://jobs.zhaopin.com/CC123.htm", "ZHILIAN"), "https://jobs.zhaopin.com/CC123.htm");
  assert.equal(run("https://www.zhaopin.com/sou/jl489/", "ZHILIAN"), "");
  assert.equal(run("https://www.zhaopin.com/company/demo/", "ZHILIAN"), "");
  assert.equal(run("https://jobs.zhaopin.com/index.htm", "ZHILIAN"), "");
  assert.equal(run("https://www.zhaopin.com/jobdetail/demo.htm?kw=1", "ZHILIAN"), "");
  assert.equal(run("https://zhaopin.com.attacker.cn/jobdetail/demo.htm", "ZHILIAN"), "");
});

test("cloud client maps content results to the fixed backend contract", () => {
  const context = vm.createContext({ URL, console, chrome: { storage: { local: { async get() { return {}; }, async set() {}, async remove() {} } } }, crypto: require("node:crypto").webcrypto, btoa: (v) => Buffer.from(v, "binary").toString("base64"), atob: (v) => Buffer.from(v, "base64").toString("binary") });
  vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
  const Cloud = context.GetJobsCloudClient;
  const plain = (value) => JSON.parse(JSON.stringify(value));

  assert.deepEqual(plain(Cloud.normalizeContentResult({ success: true, resultCode: "DELIVERED", pageState: "SUCCESS_NOTICE" }, "BOSS")),
    { valid: true, kind: "success", payload: { resultCode: "DELIVERED", evidence: { pageState: "SUCCESS_NOTICE" } } });
  assert.deepEqual(plain(Cloud.normalizeContentResult({ success: true, resultCode: "ALREADY_DELIVERED", pageState: "ALREADY_DELIVERED" }, "BOSS")),
    { valid: true, kind: "success", payload: { resultCode: "ALREADY_DELIVERED", evidence: { pageState: "ALREADY_DELIVERED", alreadyDelivered: true } } });
  assert.deepEqual(plain(Cloud.normalizeContentResult({ success: false, failureType: "CAPTCHA_REQUIRED" }, "BOSS")),
    { valid: true, kind: "pause", code: "CAPTCHA_REQUIRED" });
  assert.deepEqual(plain(Cloud.normalizeContentResult({ success: false, failureType: "JOB_CLOSED" }, "BOSS")),
    { valid: true, kind: "fail", code: "JOB_CLOSED" });
  // 结果码/页面状态不匹配、未知 failureType 一律无效，由背景兜底 UNKNOWN_ERROR。
  assert.equal(Cloud.normalizeContentResult({ success: true, resultCode: "DELIVERED", pageState: "ALREADY_DELIVERED" }, "BOSS").valid, false);
  assert.equal(Cloud.normalizeContentResult({ success: true, resultCode: "WEIRD" }, "BOSS").valid, false);
  assert.equal(Cloud.normalizeContentResult({ success: false, failureType: "HACK" }, "BOSS").valid, false);

  const execution = { leaseId: "lease-1", executionId: "ab12cd34ef56", version: 4 };
  const delivered = Cloud.buildReportPayload(execution, "DELIVERED", "2026-08-13T10:00:00.000Z");
  assert.deepEqual(plain(delivered), {
    kind: "success",
    payload: {
      leaseId: "lease-1", executionId: "ab12cd34ef56", version: 4,
      completedAt: "2026-08-13T10:00:00.000Z",
      resultCode: "DELIVERED",
      evidence: { pageState: "SUCCESS_NOTICE" }
    }
  });
  const already = Cloud.buildReportPayload(execution, "ALREADY_DELIVERED", "2026-08-13T10:00:00.000Z");
  assert.equal(already.payload.resultCode, "ALREADY_DELIVERED");
  assert.equal(already.payload.evidence.alreadyDelivered, true);
  const paused = Cloud.buildReportPayload(execution, "USER_ACTION_REQUIRED", "2026-08-13T10:00:00.000Z");
  assert.equal(paused.kind, "pause");
  assert.equal(paused.payload.reason, "USER_ACTION_REQUIRED");
  assert.ok(paused.payload.message && !paused.payload.message.includes("greeting"));
  const failed = Cloud.buildReportPayload(execution, "BUTTON_NOT_FOUND", "2026-08-13T10:00:00.000Z");
  assert.equal(failed.kind, "fail");
  assert.equal(failed.payload.errorCode, "BUTTON_NOT_FOUND");
  assert.equal(failed.payload.retryable, false);

  // 固定消息必须满足后端单行/敏感词限制。
  for (const entry of Object.values(Cloud.REPORT_MAP)) {
    assert.ok(entry.message.length <= 200);
    assert.ok(!/[\r\n]/.test(entry.message));
    assert.ok(!/cookie|authorization|bearer|password|token=|localstorage|sessionstorage/i.test(entry.message));
    assert.ok(!/\?[^\s]*=/.test(entry.message));
  }
});

test("cloud client wake validation enforces source, type, UUID taskId and bounded requestId", () => {
  const context = vm.createContext({ URL, console, chrome: undefined, crypto: require("node:crypto").webcrypto, btoa: (v) => Buffer.from(v, "binary").toString("base64") });
  vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
  const Cloud = context.GetJobsCloudClient;

  const valid = Cloud.normalizeWakeMessage({ source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "req-1" });
  assert.equal(valid.success, true);
  assert.equal(valid.taskId, TEST_TASK_ID);

  assert.equal(Cloud.normalizeWakeMessage({ source: "GET_JOBS_PAGE", type: "BOSS_DELIVER_ONE", taskId: TEST_TASK_ID, requestId: "r" }).success, false);
  assert.equal(Cloud.normalizeWakeMessage({ source: "GET_JOBS_PLATFORM", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "r" }).success, false);
  assert.equal(Cloud.normalizeWakeMessage({ source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: "not-a-uuid", requestId: "r" }).success, false);
  assert.equal(Cloud.normalizeWakeMessage({ source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "x".repeat(129) }).success, false);
  assert.equal(Cloud.normalizeWakeMessage({ source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "bad id" }).success, false);
});

test("execution state writes reject missing or invalid critical fields", async () => {
  let written = null;
  const context = vm.createContext({
    URL,
    console,
    chrome: {
      storage: {
        local: {
          async get() { return {}; },
          async set(values) { written = values; },
          async remove() {}
        }
      }
    },
    crypto: require("node:crypto").webcrypto,
    btoa: (v) => Buffer.from(v, "binary").toString("base64"),
    atob: (v) => Buffer.from(v, "base64").toString("binary")
  });
  vm.runInContext(read("cloud-client.js"), context, { filename: "cloud-client.js" });
  const Cloud = context.GetJobsCloudClient;
  const base = {
    taskId: TEST_TASK_ID,
    executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
    startIdempotencyKey: "start_abc",
    pendingVersion: 3,
    phase: "starting",
    leaseId: null,
    leaseExpiresAt: ""
  };
  assert.equal(await Cloud.writeExecutionState(null), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, taskId: "not-a-uuid" }), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, executionId: "" }), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, executionId: "   " }), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, startIdempotencyKey: "" }), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, phase: "unknown" }), false);
  // 无 lease 的 executing/reporting 不得写入 storage。
  assert.equal(await Cloud.writeExecutionState({ ...base, phase: "executing", leaseId: null }), false);
  assert.equal(await Cloud.writeExecutionState({ ...base, phase: "reporting", leaseId: "" }), false);
  // reporting 必须携带可重放的有效报告。
  assert.equal(await Cloud.writeExecutionState({ ...base, phase: "reporting", leaseId: "lease-1", report: null }), false);
  assert.equal(await Cloud.writeExecutionState({
    ...base,
    phase: "reporting",
    leaseId: "lease-1",
    report: { kind: "fail", payload: {}, reportIdempotencyKey: "" }
  }), false);
  assert.equal(await Cloud.writeExecutionState({
    ...base,
    phase: "reporting",
    leaseId: "lease-1",
    report: { kind: "fail", payload: {}, reportIdempotencyKey: "x".repeat(129) }
  }), false);
  assert.equal(written, null, "非法状态绝不能落入 storage");
  // 合法状态可写入并保留 leaseExpiresAt。
  assert.equal(await Cloud.writeExecutionState({
    ...base,
    phase: "executing",
    leaseId: "lease-1",
    leaseExpiresAt: "2026-08-13T10:00:00Z",
    task: { platform: "BOSS", jobUrl: "https://www.zhipin.com/job_detail/abc.html", greeting: "" }
  }), true);
  assert.equal(written["__GET_JOBS_CLOUD_EXECUTION__"].phase, "executing");
  assert.equal(written["__GET_JOBS_CLOUD_EXECUTION__"].leaseExpiresAt, "2026-08-13T10:00:00Z");
});

// ---------------------------------------------------------------- 4. static security checks

test("cloud paths never add polling timers, alarms, storage.sync or onStartup pickup", () => {
  for (const file of ["background.js", "cloud-client.js", "popup.js", "page-bridge.js"]) {
    const source = read(file);
    assert.ok(!source.includes("setInterval"), `${file} 不得使用 setInterval`);
    assert.ok(!source.includes("chrome.alarms"), `${file} 不得使用 chrome.alarms`);
    assert.ok(!source.includes("storage.sync"), `${file} 不得使用 chrome.storage.sync`);
    assert.ok(!source.includes("onStartup"), `${file} 不得使用 onStartup`);
  }
});

test("cloud delivery code never falls back to Math.random for security material", () => {
  // cloud-client/popup 全部禁；background 只检查 Cloud 投递区（legacy 扫描
  // ownerToken 的 Math.random 属于 V1-V6 既有代码，不在本批改动范围）。
  assert.ok(!read("cloud-client.js").includes("Math.random"), "cloud-client.js 不得使用 Math.random");
  assert.ok(!read("popup.js").includes("Math.random"), "popup.js 不得使用 Math.random");
  const backgroundSource = read("background.js");
  const marker = "// ---------------------------------------------------------------- Cloud 投递执行";
  const cloudSection = backgroundSource.slice(backgroundSource.indexOf(marker));
  assert.ok(cloudSection.length > 1000, "Cloud 投递区存在");
  assert.ok(!cloudSection.includes("Math.random"), "background Cloud 区不得使用 Math.random");
  assert.ok(!cloudSection.includes("setInterval"), "background Cloud 区不得轮询");
});

test("cloudManaged content paths never call the legacy delivery-result APIs", () => {
  const bossSource = read("boss-content.js");
  const bossCloud = functionBody(bossSource, "deliverCloudManagedOnCurrentPage");
  assert.ok(bossCloud.length > 200, "Boss cloud 分支实现存在");
  assert.ok(!bossCloud.includes("postDeliveryResult"), "Boss cloud 分支不得调用旧投递结果接口");
  assert.ok(!bossCloud.includes("callBossLocalApi"), "Boss cloud 分支不得调用旧本地接口");
  assert.ok(!bossCloud.includes("delivery-result"), "Boss cloud 分支不得出现旧接口路径");
  assert.ok(!bossCloud.includes("console.log"), "Boss cloud 分支不得输出日志");
  assert.ok(bossCloud.includes("ALREADY_DELIVERED"), "Boss 已沟通状态映射存在");
  // 已沟通路径必须先于 greeting 发送出现，且整个分支只发送一次 greeting。
  const alreadyIndex = bossCloud.indexOf("ALREADY_DELIVERED");
  const greetingIndex = bossCloud.indexOf("sendConfiguredGreeting");
  assert.ok(alreadyIndex >= 0 && greetingIndex > alreadyIndex, "已沟通判定必须先于 greeting 发送");
  assert.equal(bossCloud.match(/sendConfiguredGreeting\(/g).length, 1, "Boss cloud 分支只发送一次 greeting");
  // 点击前必须已保存基准 URL：同步跳转会丢失点击前的页面基准。
  const beforeUrlIndex = bossCloud.indexOf("const beforeUrl = window.location.href;");
  const chatClickIndex = bossCloud.indexOf("clickElement(chatButton)");
  assert.ok(beforeUrlIndex >= 0, "cloud 分支保存点击前基准 URL");
  assert.ok(chatClickIndex > beforeUrlIndex, "基准 URL 赋值必须先于点击立即沟通");

  const zhilianSource = read("zhilian-content.js");
  const zhilianCloud = functionBody(zhilianSource, "deliverCloudManagedOnCurrentPage");
  assert.ok(zhilianCloud.length > 200, "智联 cloud 分支实现存在");
  assert.ok(!zhilianCloud.includes("postDeliveryResult"), "智联 cloud 分支不得调用旧投递结果接口");
  assert.ok(!zhilianCloud.includes("requestZhilianLocalApi"), "智联 cloud 分支不得调用旧本地接口");
  assert.ok(!zhilianCloud.includes("delivery-result"), "智联 cloud 分支不得出现旧接口路径");
  assert.ok(!zhilianCloud.includes("greeting"), "智联不得使用 greeting");
  assert.ok(zhilianCloud.includes("USER_ACTION_REQUIRED"), "智联无法确认时必须 pause");
});

// ---------------------------------------------------------------- 5. popup

test("popup bind sends the exact bind contract and stores the token only in storage.local", async () => {
  let bindBody = null;
  const popup = loadPopup({
    fetchImpl: async (call) => {
      if (call.url.endsWith("/api/plugin/bind")) {
        bindBody = JSON.parse(call.options.body);
        return jsonResponse(envelope({
          device: { id: "dev-1", deviceName: "测试设备", browserName: "Chrome", browserVersion: "120", extensionVersion: "1.4.0", status: "ACTIVE", capabilities: ["BOSS", "ZHILIAN"], boundAt: "2026-08-01T00:00:00Z" },
          token: { value: TOKEN_SENTINEL, expiresAt: "2026-09-01T00:00:00Z", scopes: ["device:read", "tasks:read", "tasks:write"] }
        }));
      }
      return jsonResponse(envelope({}));
    }
  });

  popup.elements.get("api-base-select").value = API_BASE;
  popup.elements.get("bind-code-input").value = "ab12c-def34";
  popup.elements.get("device-name-input").value = "我的 Chrome";
  await popup.elements.get("bind-button").listeners.click();

  assert.ok(bindBody, "绑定请求必须发出");
  assert.equal(bindBody.bindCode, "AB12C-DEF34");
  assert.equal(bindBody.deviceName, "我的 Chrome");
  assert.match(bindBody.installationId, /^[A-Za-z0-9_-]{16,128}$/);
  assert.equal(bindBody.extensionVersion, "1.4.0");
  assert.equal(bindBody.browserName, "Chrome");
  assert.equal(bindBody.browserVersion, "120");
  assert.deepEqual(bindBody.capabilities, ["BOSS", "ZHILIAN"]);
  assert.ok(!Object.prototype.hasOwnProperty.call(bindBody, "userId"), "不得携带 userId");
  assert.ok(!Object.prototype.hasOwnProperty.call(bindBody, "token"), "请求不得携带 token");

  // Token 只落 chrome.storage.local；sync 从未被调用；任何消息/文本不含 Token。
  assert.equal(popup.storage["__GET_JOBS_CLOUD_BOUND__"].token, TOKEN_SENTINEL);
  assert.equal(popup.syncCalls.length, 0);
  assert.ok(!popup.elements.get("message").textContent.includes(TOKEN_SENTINEL));
});

test("popup bind failure never persists a token", async () => {
  const popup = loadPopup({
    fetchImpl: async () => jsonResponse(errorEnvelope(401, "BIND_CODE_INVALID", "绑定码无效"), 401)
  });
  popup.elements.get("api-base-select").value = API_BASE;
  popup.elements.get("bind-code-input").value = "ab12c-def34";
  popup.elements.get("device-name-input").value = "我的 Chrome";
  await popup.elements.get("bind-button").listeners.click();

  assert.equal(popup.storage["__GET_JOBS_CLOUD_BOUND__"], undefined);
  assert.ok(popup.elements.get("message").textContent.includes("BIND_CODE_INVALID"));
  assert.ok(!popup.elements.get("message").textContent.includes("http"), "错误提示不得拼入完整响应");
});

test("popup renders only approved API bases and never writes outside storage.local", () => {
  const popup = loadPopup();
  const select = popup.elements.get("api-base-select");
  assert.ok(select.children.length === 6, `应只有 6 个批准入口，实际 ${select.children.length}`);
  const values = select.children.map((option) => option.value);
  assert.deepEqual(values, [
    "http://localhost:8080", "http://127.0.0.1:8080",
    "http://localhost:8888", "http://127.0.0.1:8888",
    "http://localhost:6866", "http://127.0.0.1:6866"
  ]);
});

test("popup clears the local token when /me reports a stable token error", async () => {
  const popup = loadPopup({
    storage: {
      "__GET_JOBS_CLOUD_BOUND__": boundState()
    },
    fetchImpl: async () => jsonResponse(errorEnvelope(401, "PLUGIN_TOKEN_EXPIRED", "插件凭证已过期"), 401)
  });
  await waitUntil(() => popup.storage["__GET_JOBS_CLOUD_BOUND__"] === undefined);
  assert.equal(popup.syncCalls.length, 0);
  assert.equal(popup.elements.get("bind-section").className, "", "回到绑定界面");
  assert.ok(popup.elements.get("status-section").className.includes("hidden"), "状态界面隐藏");
  assert.ok(popup.elements.get("message").textContent.includes("PLUGIN_TOKEN_EXPIRED"), "提示稳定错误码");
});

test("popup keeps the bound view when /me succeeds", async () => {
  const popup = loadPopup({
    storage: { "__GET_JOBS_CLOUD_BOUND__": boundState() },
    fetchImpl: async () => jsonResponse(envelope({
      user: { id: "u-1", displayName: "用户A" },
      device: { id: "dev-1", deviceName: "测试设备", browserName: "Chrome", browserVersion: "120", extensionVersion: "1.4.0", status: "ACTIVE", capabilities: ["BOSS", "ZHILIAN"], boundAt: "2026-08-01T00:00:00Z" },
      token: { scopes: ["device:read"], expiresAt: "2026-09-01T00:00:00Z" }
    }))
  });
  await waitUntil(() => popup.elements.get("status-text").textContent.includes("已连接"));
  assert.ok(popup.storage["__GET_JOBS_CLOUD_BOUND__"]?.token === TOKEN_SENTINEL);
});

// ---------------------------------------------------------------- 6. 安全随机

async function runBindAttempt(popup) {
  popup.elements.get("api-base-select").value = API_BASE;
  popup.elements.get("bind-code-input").value = "ab12c-def34";
  popup.elements.get("device-name-input").value = "我的 Chrome";
  await popup.elements.get("bind-button").listeners.click();
}

for (const brokenCrypto of [null, { getRandomValues() { throw new Error("crypto unavailable"); } }]) {
  test("popup bind without secure random shows a generic error and never binds or persists", async () => {
    let bindAttempts = 0;
    const popup = loadPopup({
      crypto: brokenCrypto,
      fetchImpl: async (call) => {
        if (call.url.endsWith("/api/plugin/bind")) bindAttempts += 1;
        return jsonResponse(envelope({}));
      }
    });
    await runBindAttempt(popup);
    assert.equal(bindAttempts, 0, "安全随机不可用不得发送 bind 请求");
    assert.equal(popup.storage["__GET_JOBS_CLOUD_INSTALLATION_ID__"], undefined, "不得持久化弱安装 ID");
    assert.equal(popup.storage["__GET_JOBS_CLOUD_BOUND__"], undefined, "不得持久化绑定状态");
    assert.equal(popup.elements.get("message").textContent, "绑定过程中出现异常，请重试。", "稳定通用错误");
  });
}
