const assert = require("node:assert/strict");
const test = require("node:test");

const harness = require("./cloud-test-harness.cjs");
const {
  EXTENSION_ORIGIN,
  TEST_TASK_ID,
  OTHER_TASK_ID,
  API_BASE,
  TOKEN_SENTINEL,
  GREETING_SENTINEL,
  LEASE_SENTINEL,
  BOSS_JOB_URL,
  ZHILIAN_JOB_URL,
  loadCloudBackground,
  boundState,
  fetchSuccessChain,
  jsonResponse,
  envelope,
  errorEnvelope,
  safeString,
  waitUntil
} = harness;

const PAGE_SENDER = { tab: { id: 20, url: "http://localhost:6866/delivery" } };
const STABLE_RESPONSE_KEYS = ["success", "accepted", "taskId", "state", "code", "message"];

function pageEvents(h, tabId = 20) {
  return h.sentMessages
    .filter((entry) => entry.tabId === tabId && entry.message.type === "GET_JOBS_EXTENSION_EVENT")
    .map((entry) => entry.message.payload);
}

function fetchByPattern(calls, pattern) {
  return calls.filter((call) => pattern.test(call.url));
}

function assertNoSensitiveLeaks(h, extraSentinels = []) {
  // lease/executionId 是 Cloud finish 请求契约字段，允许出现在 finish body；
  // Token 只允许出现在 Authorization Header；greeting 只允许出现在发给 Boss
  // content script 的受控任务里。除此之外任何通道都不得出现这些值。
  const sentinels = [TOKEN_SENTINEL, GREETING_SENTINEL, LEASE_SENTINEL, ...extraSentinels];
  const pageTraffic = h.sentMessages
    .filter((entry) => entry.tabId === 20)
    .map((entry) => safeString(entry.message));
  for (const text of pageTraffic) {
    for (const sentinel of sentinels) {
      assert.ok(!text.includes(sentinel), `页面消息泄漏 ${sentinel}: ${text.slice(0, 200)}`);
    }
  }
  for (const call of h.fetchCalls) {
    const body = call.options?.body ? safeString(call.options.body) : "";
    assert.ok(!body.includes(TOKEN_SENTINEL), "Token 不得出现在任何请求体");
    assert.ok(!body.includes(GREETING_SENTINEL), "greeting 不得出现在任何请求体");
    const headers = safeString(call.options?.headers || {});
    assert.ok(!headers.includes(LEASE_SENTINEL), "lease 不得出现在任何请求头");
    assert.ok(!headers.includes(GREETING_SENTINEL), "请求头不得携带 greeting");
    // Authorization 之外不得出现 Token。
    const authHeader = String(call.options?.headers?.Authorization || "");
    const otherHeaders = safeString({ ...(call.options?.headers || {}) });
    if (otherHeaders.includes(TOKEN_SENTINEL) && !authHeader.includes(TOKEN_SENTINEL)) {
      assert.fail("Token 出现在非 Authorization 请求头");
    }
  }
  // 发给招聘平台 content script 的任务只允许 url/id/greeting。
  const contentTasks = h.sentMessages
    .filter((entry) => entry.message.cloudManaged)
    .map((entry) => safeString(entry.message.task));
  for (const text of contentTasks) {
    assert.ok(!text.includes(TOKEN_SENTINEL), "content task 泄漏 Token");
    assert.ok(!text.includes(LEASE_SENTINEL), "content task 泄漏 lease");
    assert.ok(!text.includes("executionId"), "content task 泄漏 executionId");
    assert.ok(!text.includes("Idempotency"), "content task 泄漏幂等键");
  }
  for (const entry of h.consoleLogs) {
    const text = entry.join(" ");
    for (const sentinel of sentinels) {
      assert.ok(!text.includes(sentinel), `console 泄漏 ${sentinel}`);
    }
  }
  assert.equal(h.syncCalls.length, 0, "同步存储不得被调用");
}

// ---------------------------------------------------------------- 唤醒校验

test("wake without bound state returns PLUGIN_NOT_BOUND and never touches the network", async () => {
  const h = loadCloudBackground({ bound: null });
  const response = await h.wake(TEST_TASK_ID);
  assert.equal(response.success, false);
  assert.equal(response.code, "PLUGIN_NOT_BOUND");
  assert.equal(h.fetchCalls.length, 0, "未绑定不得发起任何 Cloud 请求");
});

test("wake with a malformed taskId is rejected before any storage or network access", async () => {
  const h = loadCloudBackground({ bound: boundState() });
  const response = await h.wake("not-a-uuid");
  assert.equal(response.success, false);
  assert.equal(response.code, "VALIDATION_ERROR");
  assert.equal(h.fetchCalls.length, 0);
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined);
});

test("idle extension never polls pending: no fetch happens until an explicit wake", async () => {
  const h = loadCloudBackground({ bound: boundState() });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(h.fetchCalls.length, 0, "空闲时不得有任何 pending 轮询");
});

test("wake from unapproved page origins or platform pages is rejected before anything else", async () => {
  const h = loadCloudBackground({ bound: boundState() });
  const message = { source: "GET_JOBS_PAGE", type: "CLOUD_DELIVERY_WAKE", taskId: TEST_TASK_ID, requestId: "req-1" };

  const fromRandomSite = await h.dispatchRuntimeMessage(message, { tab: { id: 30, url: "http://localhost:9999/delivery" } });
  assert.equal(fromRandomSite.success, false);
  assert.match(fromRandomSite.message, /不允许/);

  const fromBossPage = await h.dispatchRuntimeMessage(message, { tab: { id: 1, url: "https://www.zhipin.com/job_detail/x.html" } });
  assert.equal(fromBossPage.success, false);
  assert.equal(h.fetchCalls.length, 0, "被拒绝的唤醒不得触发网络请求");

  const fromGateway = await h.dispatchRuntimeMessage(message, { tab: { id: 31, url: "http://localhost:8080/delivery" } });
  assert.equal(fromGateway.accepted, true, "批准的 8080 网页来源允许唤醒");
});

// ---------------------------------------------------------------- 精确 taskId 领取与 start

test("wake fetches pending, starts only the exact taskId and replies with stable fields only", async () => {
  const h = loadCloudBackground({ bound: boundState(), fetchImpl: fetchSuccessChain() });
  const response = await h.wake(TEST_TASK_ID, "req-1");
  assert.equal(response.success, true);
  assert.equal(response.accepted, true);
  assert.equal(response.taskId, TEST_TASK_ID);
  assert.deepEqual(Object.keys(response).sort(), STABLE_RESPONSE_KEYS.sort());

  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  assert.ok(done, "完整链路应回传 success");

  const pendingCall = fetchByPattern(h.fetchCalls, /\/api\/plugin\/tasks\/pending\?limit=20$/)[0];
  assert.ok(pendingCall, "先调用 pending");
  assert.equal(pendingCall.options.headers.Authorization, `Bearer ${TOKEN_SENTINEL}`);

  const startCall = fetchByPattern(h.fetchCalls, new RegExp(`/api/plugin/tasks/${TEST_TASK_ID}/start$`))[0];
  assert.ok(startCall, "对精确 taskId 调用 start");
  const startBody = JSON.parse(startCall.options.body);
  assert.equal(startBody.version, 3, "使用 pending 返回的版本号");
  assert.match(startBody.executionId, /^[0-9a-f]{32}$/, "executionId 为 128-bit 随机值");
  assert.equal(startBody.extensionVersion, "1.4.0", "使用 manifest 数字版本");
  assert.ok(!Object.prototype.hasOwnProperty.call(startBody, "pageUrl"), "不得发送网页 URL");
  assert.match(startCall.options.headers["Idempotency-Key"], /^start_[A-Za-z0-9_-]+$/);

  // 导航 URL 与 greeting 只来自 start 响应。
  const deliverMessage = h.sentMessages.find((entry) => entry.message.cloudManaged);
  assert.ok(deliverMessage, "向平台页面发送 cloudManaged 投递任务");
  assert.equal(deliverMessage.message.task.url, BOSS_JOB_URL);
  assert.equal(deliverMessage.message.task.greeting, GREETING_SENTINEL);
  assert.equal(deliverMessage.message.task.id, TEST_TASK_ID);
  const contentTaskText = safeString(deliverMessage.message.task);
  assert.ok(!contentTaskText.includes(TOKEN_SENTINEL), "Token 不得进入 content script");
  assert.ok(!contentTaskText.includes(LEASE_SENTINEL), "lease 不得进入 content script");
  assert.ok(!contentTaskText.includes("executionId"), "executionId 不得进入 content script");

  // 执行成功后清理活动状态，只保留最近结果。
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined);
  const recent = h.storage["__GET_JOBS_CLOUD_RECENT_RESULTS__"];
  assert.ok(Array.isArray(recent) && recent.some((item) => item.taskId === TEST_TASK_ID && item.status === "SUCCEEDED"));
  assertNoSensitiveLeaks(h);
});

test("pending fetch is authorized and pending result without the taskId reports TASK_NOT_AVAILABLE", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [{ ...harness.defaultPendingItem(), id: OTHER_TASK_ID }], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
      }
      return jsonResponse(envelope({}));
    }
  });
  const response = await h.wake(TEST_TASK_ID);
  assert.equal(response.accepted, true);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "TASK_NOT_AVAILABLE"));
  assert.ok(done, "应报告 TASK_NOT_AVAILABLE");
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 0, "不得对未找到的任务调用 start");
  const recent = h.storage["__GET_JOBS_CLOUD_RECENT_RESULTS__"];
  assert.ok(recent.some((item) => item.taskId === TEST_TASK_ID && item.code === "TASK_NOT_AVAILABLE"));
});

test("token failure on pending clears the local token and stops", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async () => jsonResponse(errorEnvelope(401, "PLUGIN_TOKEN_EXPIRED", "插件凭证已过期"), 401)
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => h.storage["__GET_JOBS_CLOUD_BOUND__"] === undefined);
  assert.ok(done, "401 稳定错误码必须清理本机 Token");
  assert.ok(pageEvents(h).some((event) => event.code === "PLUGIN_TOKEN_EXPIRED"));
});

// ---------------------------------------------------------------- 单任务与去重

test("same taskId wake during execution resumes; a different taskId gets PLUGIN_BUSY", async () => {
  let releaseStart;
  const startGate = new Promise((resolve) => { releaseStart = resolve; });
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) {
        await startGate;
        return jsonResponse(envelope(harness.defaultStartData()));
      }
      return jsonResponse(envelope({}));
    }
  });

  const first = await h.wake(TEST_TASK_ID, "req-1");
  assert.equal(first.accepted, true);
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/start$/).length === 1);

  const busy = await h.wake(OTHER_TASK_ID, "req-2");
  assert.equal(busy.success, false);
  assert.equal(busy.code, "PLUGIN_BUSY");

  const resumed = await h.wake(TEST_TASK_ID, "req-3");
  assert.equal(resumed.accepted, true);
  assert.equal(resumed.state, "resuming");

  releaseStart();
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  assert.ok(done);
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 1, "同 task 不重复 start");
  assert.equal(fetchByPattern(h.fetchCalls, /\/pending/).length, 1, "重复唤醒不重复拉取 pending");
});

// ---------------------------------------------------------------- 持久化与重放

test("execution metadata is persisted before start; a lost start response replays the same request", async () => {
  const sharedStorage = {};
  let firstStartBody = null;
  let persistedBeforeStart = null;

  const offlineChain = async (call, state) => {
    if (call.url.includes("/pending")) {
      return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
    }
    if (/\/start$/.test(call.url)) {
      firstStartBody = JSON.parse(call.options.body);
      persistedBeforeStart = state.storage["__GET_JOBS_CLOUD_EXECUTION__"];
      throw new Error("network down");
    }
    return jsonResponse(envelope({}));
  };

  const h1 = loadCloudBackground({ storage: sharedStorage, bound: boundState(), fetchImpl: offlineChain });
  await h1.wake(TEST_TASK_ID);
  const offline = await waitUntil(() => pageEvents(h1).some((event) => event.stage === "offline"));
  assert.ok(offline, "网络失败应报告 offline");

  // start 请求前已持久化稳定 execution/key（请求发出那一刻的状态）。
  assert.ok(persistedBeforeStart, "start 前必须已持久化");
  assert.equal(persistedBeforeStart.taskId, TEST_TASK_ID);
  assert.equal(persistedBeforeStart.phase, "starting");
  assert.equal(persistedBeforeStart.executionId, firstStartBody.executionId);
  assert.ok(persistedBeforeStart.startIdempotencyKey, "幂等键已持久化");

  // 模拟 Service Worker 重启：新 VM 共享同一 storage，再次显式唤醒。
  let replayBody = null;
  const replayChain = async (call) => {
    if (/\/start$/.test(call.url)) {
      replayBody = JSON.parse(call.options.body);
      return jsonResponse(envelope(harness.defaultStartData()));
    }
    if (/\/success$/.test(call.url)) return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCEEDED", finishedAt: new Date().toISOString(), version: 5 }));
    return jsonResponse(envelope({}));
  };
  const h2 = loadCloudBackground({ storage: sharedStorage, bound: boundState(), fetchImpl: replayChain });
  const response = await h2.wake(TEST_TASK_ID, "req-2");
  assert.equal(response.state, "resuming");
  const done = await waitUntil(() => fetchByPattern(h2.fetchCalls, /\/success$/).length > 0);
  assert.ok(done);

  const replayCall = fetchByPattern(h2.fetchCalls, /\/start$/)[0];
  assert.equal(replayCall.options.headers["Idempotency-Key"], persistedBeforeStart.startIdempotencyKey, "必须重放同一 Idempotency-Key");
  assert.equal(replayBody.executionId, firstStartBody.executionId, "必须复用同一 executionId");
  assert.equal(replayBody.version, firstStartBody.version);
  assert.equal(replayBody.extensionVersion, firstStartBody.extensionVersion);
});

test("a report is persisted before finish and replayed verbatim after a restart", async () => {
  const sharedStorage = {};
  let firstReportKey = null;
  let firstReportBody = null;

  const flakyChain = async (call) => {
    if (call.url.includes("/pending")) {
      return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
    }
    if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
    if (/\/success$/.test(call.url)) {
      firstReportKey = call.options.headers["Idempotency-Key"];
      firstReportBody = JSON.parse(call.options.body);
      throw new Error("network down during report");
    }
    return jsonResponse(envelope({}));
  };

  const h1 = loadCloudBackground({ storage: sharedStorage, bound: boundState(), fetchImpl: flakyChain });
  await h1.wake(TEST_TASK_ID);
  const reported = await waitUntil(() => fetchByPattern(h1.fetchCalls, /\/success$/).length === 1);
  assert.ok(reported);
  const persisted = sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"];
  assert.equal(persisted.phase, "reporting");
  assert.equal(persisted.report.kind, "success");
  assert.equal(persisted.report.reportIdempotencyKey, firstReportKey);
  assert.equal(persisted.report.payload.resultCode, "DELIVERED");

  const replayChain = async (call) => {
    if (/\/success$/.test(call.url)) {
      return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCEEDED", finishedAt: new Date().toISOString(), version: 5 }));
    }
    return jsonResponse(envelope({}));
  };
  const h2 = loadCloudBackground({ storage: sharedStorage, bound: boundState(), fetchImpl: replayChain });
  await h2.wake(TEST_TASK_ID, "req-2");
  const done = await waitUntil(() => sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"] === undefined);
  assert.ok(done, "回传成功后清理活动执行状态");

  const replayCall = fetchByPattern(h2.fetchCalls, /\/success$/)[0];
  assert.ok(replayCall, "重放 finish 请求");
  assert.equal(replayCall.options.headers["Idempotency-Key"], firstReportKey, "同一报告幂等键");
  assert.deepEqual(JSON.parse(replayCall.options.body), firstReportBody, "原样重放同一报告");
  assert.equal(fetchByPattern(h2.fetchCalls, /\/pending/).length, 0, "重放阶段不再拉取 pending");
  assert.equal(fetchByPattern(h2.fetchCalls, /\/start$/).length, 0, "重放阶段不再 start");
});

test("a 409 conflict on start clears the run and never retries unbounded", async () => {
  let startAttempts = 0;
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) {
        startAttempts += 1;
        return jsonResponse(errorEnvelope(409, "TASK_ALREADY_CLAIMED", "任务已被其他设备领取"), 409);
      }
      return jsonResponse(envelope({}));
    }
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "TASK_ALREADY_CLAIMED"));
  assert.ok(done, "报告稳定错误码");
  assert.equal(startAttempts, 1, "409 不得无界自动重试");
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined, "冲突后清理执行状态");
});

// ---------------------------------------------------------------- 结果映射

test("content results map to success/pause/fail backend calls with fixed messages", async () => {
  const cases = [
    { content: { success: true, resultCode: "ALREADY_DELIVERED", pageState: "ALREADY_DELIVERED" }, action: "success", expect: "ALREADY_DELIVERED" },
    { content: { success: false, failureType: "LOGIN_REQUIRED" }, action: "pause", expect: "LOGIN_REQUIRED" },
    { content: { success: false, failureType: "CAPTCHA_REQUIRED" }, action: "pause", expect: "CAPTCHA_REQUIRED" },
    { content: { success: false, failureType: "RISK_CONTROL" }, action: "pause", expect: "RISK_CONTROL" },
    { content: { success: false, failureType: "PAGE_CHANGED" }, action: "pause", expect: "PAGE_CHANGED" },
    { content: { success: false, failureType: "USER_ACTION_REQUIRED" }, action: "pause", expect: "USER_ACTION_REQUIRED" },
    { content: { success: false, failureType: "JOB_CLOSED" }, action: "fail", expect: "JOB_CLOSED" },
    { content: { success: false, failureType: "BUTTON_NOT_FOUND" }, action: "fail", expect: "BUTTON_NOT_FOUND" },
    { content: { success: false, failureType: "UNKNOWN_ERROR" }, action: "fail", expect: "UNKNOWN_ERROR" }
  ];
  for (const item of cases) {
    const h = loadCloudBackground({ bound: boundState(), contentResult: item.content, fetchImpl: fetchSuccessChain() });
    await h.wake(TEST_TASK_ID, `req-${item.expect}`);
    const done = await waitUntil(() => fetchByPattern(h.fetchCalls, new RegExp(`/${item.action}$`)).length > 0);
    assert.ok(done, `${item.expect} 应回传 ${item.action}`);
    const call = fetchByPattern(h.fetchCalls, new RegExp(`/${item.action}$`))[0];
    const body = JSON.parse(call.options.body);
    assert.equal(body.leaseId, LEASE_SENTINEL);
    assert.match(body.executionId, /^[0-9a-f]{32}$/);
    assert.equal(body.version, 4);
    if (item.action === "pause") {
      assert.equal(body.reason, item.expect);
      assert.ok(body.message && body.message.length <= 200, "固定消息");
      assert.ok(!/[\r\n]/.test(body.message));
      assert.ok(!/\?[^\s]*=/.test(body.message));
    } else if (item.action === "fail") {
      assert.equal(body.errorCode, item.expect);
      assert.equal(body.retryable, false, "客户端不上报可重试性");
    } else {
      assert.equal(body.resultCode, item.expect);
      assert.equal(body.evidence.pageState, "ALREADY_DELIVERED");
      assert.equal(body.evidence.alreadyDelivered, true);
    }
    assert.ok(Number.isFinite(Date.parse(body.completedAt || body.pausedAt || body.failedAt)), "时间使用当前 ISO 时间");
    // 映射为 pause 时不得继续点击/投递：只发了一次 content 消息。
    if (item.action === "pause" && item.content.failureType === "CAPTCHA_REQUIRED") {
      assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 1, "验证码不得继续点击");
    }
  }
});

test("invalid content responses fall back to fail UNKNOWN_ERROR", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: { success: true, resultCode: "HACK", pageState: "SUCCESS_NOTICE" },
    fetchImpl: fetchSuccessChain()
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/fail$/).length > 0);
  assert.ok(done);
  const body = JSON.parse(fetchByPattern(h.fetchCalls, /\/fail$/)[0].options.body);
  assert.equal(body.errorCode, "UNKNOWN_ERROR");
});

// ---------------------------------------------------------------- 导航前 URL 校验与智联

test("an untrusted job URL from the start response is rejected before any navigation", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) {
        const data = harness.defaultStartData();
        data.task.jobUrl = "https://evil.example.com/job_detail/abc123.html";
        return jsonResponse(envelope(data));
      }
      return jsonResponse(envelope({}));
    }
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/pause$/).length > 0);
  assert.ok(done, "恶意 URL 应 pause PAGE_CHANGED");
  const body = JSON.parse(fetchByPattern(h.fetchCalls, /\/pause$/)[0].options.body);
  assert.equal(body.reason, "PAGE_CHANGED");
  assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 0, "不得向平台页面发送投递任务");
  assert.ok(!h.tabList.some((tab) => (tab.url || "").includes("evil.example.com")), "不得导航到恶意 URL");
});

test("Zhilian cloud task never receives a greeting and navigation re-checks the detail path", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: { success: false, failureType: "USER_ACTION_REQUIRED" },
    fetchImpl: fetchSuccessChain({ platform: "ZHILIAN" }),
    tabs: [
      { id: 20, windowId: 1, url: "http://localhost:6866/delivery", status: "complete" },
      { id: 2, windowId: 1, url: "https://www.zhaopin.com/", status: "complete" }
    ]
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/pause$/).length > 0);
  assert.ok(done);

  const deliverMessage = h.sentMessages.find((entry) => entry.message.cloudManaged);
  assert.ok(deliverMessage, "智联 content 消息存在");
  assert.equal(deliverMessage.message.task.url, ZHILIAN_JOB_URL);
  assert.ok(!Object.prototype.hasOwnProperty.call(deliverMessage.message.task, "greeting"), "智联不得接收 greeting");
  const body = JSON.parse(fetchByPattern(h.fetchCalls, /\/pause$/)[0].options.body);
  assert.equal(body.reason, "USER_ACTION_REQUIRED", "智联无法确认时 pause");
});

// ---------------------------------------------------------------- 敏感信息扫描

test("no page event, progress payload, console line or fetch body ever carries credentials", async () => {
  const h = loadCloudBackground({ bound: boundState(), fetchImpl: fetchSuccessChain() });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  assert.ok(done);

  assertNoSensitiveLeaks(h);

  const events = pageEvents(h);
  assert.ok(events.length >= 3, "至少 accepted/executing/succeeded 阶段事件");
  const allowedKeys = new Set(["taskId", "stage", "code", "message", "time", "platform", "timestamp"]);
  for (const event of events) {
    for (const key of Object.keys(event)) {
      assert.ok(allowedKeys.has(key), `Cloud 事件含越界字段 ${key}`);
    }
    assert.equal(event.taskId, TEST_TASK_ID);
    assert.ok(["accepted", "fetching", "starting", "navigating", "executing", "reporting", "succeeded", "failed", "paused", "offline"].includes(event.stage));
  }
  const stages = events.map((event) => event.stage);
  assert.ok(stages.includes("accepted") && stages.includes("fetching") && stages.includes("starting"));
  assert.ok(stages.includes("executing") || stages.includes("reporting"));
  assert.ok(stages.includes("succeeded"));

  // 所有 Cloud fetch 只打向绑定的 API base，绝不使用旧 localhost 投递结果接口。
  const cloudCalls = h.fetchCalls.filter((call) => call.url.includes("/api/plugin/"));
  assert.ok(cloudCalls.length > 0);
  for (const call of cloudCalls) {
    assert.ok(call.url.startsWith(`${API_BASE}/api/plugin/`), `Cloud 请求必须指向绑定 API base：${call.url}`);
  }
  assert.equal(h.fetchCalls.filter((call) => /\/api\/boss\/|\/api\/zhilian\/|delivery-result/.test(call.url)).length, 0, "不得调用旧投递结果接口");
});

// ---------------------------------------------------------------- 关键持久化失败

test("a failed starting-state write reports STORAGE_UNAVAILABLE before any start call", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: fetchSuccessChain(),
    failExecutionSetPhases: ["starting"]
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "STORAGE_UNAVAILABLE"));
  assert.ok(done, "应报告 STORAGE_UNAVAILABLE");
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 0, "starting 写失败绝不调用 start");
  assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 0, "绝不触发 content");
  assert.equal(h.executedScripts.length, 0, "绝不注入 content 脚本");
  assert.ok(!h.tabList.some((tab) => (tab.url || "").includes("job_detail")), "绝不导航到岗位详情页");
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined, "失败写入不得留下执行状态");
});

test("a failed executing-state write stops before navigation and keeps the starting state", async () => {
  const sharedStorage = {};
  const h = loadCloudBackground({
    storage: sharedStorage,
    bound: boundState(),
    fetchImpl: fetchSuccessChain(),
    failExecutionSetPhases: ["executing"]
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "STORAGE_UNAVAILABLE"));
  assert.ok(done, "应报告 STORAGE_UNAVAILABLE");
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 1, "start 请求本身可以发出");
  assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 0, "executing 写失败绝不触发 content");
  assert.equal(h.executedScripts.length, 0, "绝不注入 content 脚本");
  assert.ok(!h.tabList.some((tab) => (tab.url || "").includes("job_detail")), "绝不导航");
  const persisted = sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"];
  assert.equal(persisted.phase, "starting", "保留 starting 状态供同 task 用相同 start key 幂等重放");
  assert.ok(persisted.startIdempotencyKey, "幂等键仍在");
});

test("a failed reporting-state write never sends finish and keeps the executing state", async () => {
  const sharedStorage = {};
  const h = loadCloudBackground({
    storage: sharedStorage,
    bound: boundState(),
    contentResult: { success: true, resultCode: "DELIVERED", pageState: "SUCCESS_NOTICE", message: "模拟投递成功" },
    fetchImpl: fetchSuccessChain(),
    failExecutionSetPhases: ["reporting"]
  });
  await h.wake(TEST_TASK_ID);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "STORAGE_UNAVAILABLE"));
  assert.ok(done, "应报告 STORAGE_UNAVAILABLE");
  assert.equal(fetchByPattern(h.fetchCalls, /\/success$/).length, 0, "reporting 写失败绝不发送 finish");
  assert.equal(fetchByPattern(h.fetchCalls, /\/fail$/).length, 0);
  assert.equal(fetchByPattern(h.fetchCalls, /\/pause$/).length, 0);
  const persisted = sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"];
  assert.equal(persisted.phase, "executing", "保留 executing 状态，等下次同 task 唤醒重新检查页面");
  assert.ok(persisted.leaseId, "租约仍持久化");
  assert.equal(h.storage["__GET_JOBS_CLOUD_RECENT_RESULTS__"], undefined, "不得声称成功记录最近结果");
});

// ---------------------------------------------------------------- 安全随机不可用

for (const brokenCrypto of [null, { getRandomValues() { throw new Error("crypto unavailable"); } }]) {
  test("secure random unavailability reports SECURE_RANDOM_UNAVAILABLE without side effects", async () => {
    const h = loadCloudBackground({
      bound: boundState(),
      crypto: brokenCrypto,
      fetchImpl: fetchSuccessChain()
    });
    await h.wake(TEST_TASK_ID);
    const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "SECURE_RANDOM_UNAVAILABLE"));
    assert.ok(done, "应报告 SECURE_RANDOM_UNAVAILABLE");
    assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 0, "绝不调用 start");
    assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 0, "绝不触发 content");
    assert.equal(h.executedScripts.length, 0, "绝不注入 content 脚本");
    assert.ok(!h.tabList.some((tab) => (tab.url || "").includes("job_detail")), "绝不导航");
    assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined, "绝不写入执行状态");
    const failureEvent = pageEvents(h).find((event) => event.code === "SECURE_RANDOM_UNAVAILABLE");
    assert.equal(failureEvent.stage, "failed");
    assert.equal(failureEvent.message, "安全随机数不可用，无法启动投递执行", "只报告稳定消息");
    assert.equal(h.consoleLogs.length, 0, "原始异常不得写 console");
  });
}

// ---------------------------------------------------------------- 租约驱动恢复

test("a 25-minute-old starting state still blocks other tasks and replays the original start", async () => {
  const sharedStorage = {};
  const offlineChain = async (call) => {
    if (call.url.includes("/pending")) {
      return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 10, serverTime: new Date().toISOString() }));
    }
    if (/\/start$/.test(call.url)) throw new Error("network down");
    return jsonResponse(envelope({}));
  };
  const h1 = loadCloudBackground({ storage: sharedStorage, bound: boundState(), fetchImpl: offlineChain });
  await h1.wake(TEST_TASK_ID);
  const offline = await waitUntil(() => pageEvents(h1).some((event) => event.stage === "offline"));
  assert.ok(offline, "start 响应丢失模拟为 offline");
  const persisted = sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"];
  assert.equal(persisted.phase, "starting");
  // 模拟 25 分钟前写入的 starting 状态：超过任何固定新鲜窗口。
  sharedStorage["__GET_JOBS_CLOUD_EXECUTION__"] = {
    ...persisted,
    updatedAt: new Date(Date.now() - 25 * 60 * 1000).toISOString()
  };

  let startBody = null;
  const h2 = loadCloudBackground({
    storage: sharedStorage,
    bound: boundState(),
    fetchImpl: async (call) => {
      if (/\/start$/.test(call.url)) {
        startBody = JSON.parse(call.options.body);
        return jsonResponse(envelope(harness.defaultStartData()));
      }
      if (/\/success$/.test(call.url)) {
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCEEDED", finishedAt: new Date().toISOString(), version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });

  const busy = await h2.wake(OTHER_TASK_ID);
  assert.equal(busy.success, false);
  assert.equal(busy.code, "PLUGIN_BUSY", "starting 状态不得被不同 task 按本地时间清除");
  assert.equal(fetchByPattern(h2.fetchCalls, /\/pending/).length, 0, "busy 时不得拉取 pending");

  const resumed = await h2.wake(TEST_TASK_ID, "req-2");
  assert.equal(resumed.state, "resuming");
  const done = await waitUntil(() => fetchByPattern(h2.fetchCalls, /\/success$/).length > 0);
  assert.ok(done, "同 task 重放原 start 并完成");
  assert.equal(fetchByPattern(h2.fetchCalls, /\/start$/).length, 1);
  assert.equal(startBody.executionId, persisted.executionId, "复用原 executionId");
  assert.equal(
    fetchByPattern(h2.fetchCalls, /\/start$/)[0].options.headers["Idempotency-Key"],
    persisted.startIdempotencyKey,
    "复用原幂等键"
  );
});

test("an unexpired lease blocks other tasks even when updatedAt is far in the past", async () => {
  const storage = {
    "__GET_JOBS_CLOUD_EXECUTION__": {
      taskId: TEST_TASK_ID,
      executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
      startIdempotencyKey: "start_old",
      pendingVersion: 3,
      phase: "executing",
      updatedAt: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
      leaseId: LEASE_SENTINEL,
      leaseExpiresAt: new Date(Date.now() + 20 * 60 * 1000).toISOString(),
      version: 4,
      attemptNumber: 1,
      task: { platform: "BOSS", jobUrl: BOSS_JOB_URL, greeting: "" },
      report: null
    }
  };
  const h = loadCloudBackground({ storage, bound: boundState(), fetchImpl: fetchSuccessChain() });
  const busy = await h.wake(OTHER_TASK_ID);
  assert.equal(busy.success, false);
  assert.equal(busy.code, "PLUGIN_BUSY", "租约未过期时不同 task 必须 busy");
  assert.equal(storage["__GET_JOBS_CLOUD_EXECUTION__"].taskId, TEST_TASK_ID, "不得清除租约内的执行状态");
  assert.equal(h.fetchCalls.length, 0);
});

test("a missing leaseExpiresAt is treated conservatively as busy for other tasks", async () => {
  const storage = {
    "__GET_JOBS_CLOUD_EXECUTION__": {
      taskId: TEST_TASK_ID,
      executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
      startIdempotencyKey: "start_no_expiry",
      pendingVersion: 3,
      phase: "executing",
      updatedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
      leaseId: LEASE_SENTINEL,
      leaseExpiresAt: "",
      version: 4,
      attemptNumber: 1,
      task: { platform: "BOSS", jobUrl: BOSS_JOB_URL, greeting: "" },
      report: null
    }
  };
  const h = loadCloudBackground({ storage, bound: boundState(), fetchImpl: fetchSuccessChain() });
  const busy = await h.wake(OTHER_TASK_ID);
  assert.equal(busy.code, "PLUGIN_BUSY", "时间字段缺失时保守 busy，不假设安全清理");
  assert.equal(h.fetchCalls.length, 0);
});

test("an expired executing lease fails the same task with LEASE_EXPIRED before any page action", async () => {
  const storage = {
    "__GET_JOBS_CLOUD_EXECUTION__": {
      taskId: TEST_TASK_ID,
      executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
      startIdempotencyKey: "start_expired",
      pendingVersion: 3,
      phase: "executing",
      updatedAt: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
      leaseId: LEASE_SENTINEL,
      leaseExpiresAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
      version: 4,
      attemptNumber: 1,
      task: { platform: "BOSS", jobUrl: BOSS_JOB_URL, greeting: "" },
      report: null
    }
  };
  const h = loadCloudBackground({ storage, bound: boundState(), fetchImpl: fetchSuccessChain() });
  const response = await h.wake(TEST_TASK_ID, "req-expired");
  assert.equal(response.accepted, true);
  const done = await waitUntil(() => pageEvents(h).some((event) => event.code === "LEASE_EXPIRED"));
  assert.ok(done, "应报告 LEASE_EXPIRED");
  assert.equal(h.sentMessages.filter((entry) => entry.message.cloudManaged).length, 0, "租约过期不得触发 content");
  assert.equal(h.executedScripts.length, 0, "不得注入 content 脚本");
  assert.ok(!h.tabList.some((tab) => (tab.url || "").includes("job_detail")), "租约过期不得导航");
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 0, "不得重新 start");
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined, "过期执行状态被清理");

  // 下一次显式唤醒才允许重新 pending/start。
  const second = await h.wake(TEST_TASK_ID, "req-2");
  assert.equal(second.accepted, true);
  const started = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/start$/).length > 0);
  assert.ok(started, "状态清理后再次显式唤醒重新领取任务");
});

test("a stale reporting state still blocks other tasks and replays the original report", async () => {
  const reportPayload = {
    leaseId: LEASE_SENTINEL,
    executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
    version: 4,
    completedAt: "2026-08-13T10:00:00.000Z",
    resultCode: "DELIVERED",
    evidence: { pageState: "SUCCESS_NOTICE" }
  };
  const storage = {
    "__GET_JOBS_CLOUD_EXECUTION__": {
      taskId: TEST_TASK_ID,
      executionId: "ab12cd34ef56ab12cd34ef56ab12cd34",
      startIdempotencyKey: "start_rep",
      pendingVersion: 3,
      phase: "reporting",
      updatedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
      leaseId: LEASE_SENTINEL,
      leaseExpiresAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      version: 4,
      attemptNumber: 1,
      task: { platform: "BOSS", jobUrl: BOSS_JOB_URL, greeting: "" },
      report: {
        kind: "success",
        payload: reportPayload,
        reportIdempotencyKey: "report_replay_1"
      }
    }
  };
  let successBody = null;
  let successKey = null;
  const h = loadCloudBackground({
    storage,
    bound: boundState(),
    fetchImpl: async (call) => {
      if (/\/success$/.test(call.url)) {
        successBody = JSON.parse(call.options.body);
        successKey = call.options.headers["Idempotency-Key"];
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCEEDED", finishedAt: new Date().toISOString(), version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });

  const busy = await h.wake(OTHER_TASK_ID);
  assert.equal(busy.code, "PLUGIN_BUSY", "reporting 无论多旧都不得被不同 task 清除");
  assert.equal(h.fetchCalls.length, 0, "busy 时不得发起任何 Cloud 请求");

  await h.wake(TEST_TASK_ID, "req-replay");
  const done = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length === 1);
  assert.ok(done, "同 task 原样重放原报告");
  assert.equal(successKey, "report_replay_1", "复用原报告幂等键");
  assert.deepEqual(successBody, reportPayload, "原样重放同一报告载荷");
  assert.equal(fetchByPattern(h.fetchCalls, /\/start$/).length, 0, "重放阶段不再 start");
  assert.equal(fetchByPattern(h.fetchCalls, /\/pending/).length, 0, "重放阶段不再 pending");
  assert.equal(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], undefined, "回传成功后清理执行状态");
});
