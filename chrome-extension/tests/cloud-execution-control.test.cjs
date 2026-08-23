/**
 * P8 执行队列与暂停协议测试：
 * - popup 显式开始后才运行执行队列，空闲绝不轮询；
 * - 连续失败达到 FAILURE_THRESHOLD 时调用 batch-pause、持久化暂停并停止本轮；
 * - 成功重置失败计数；
 * - 登录/验证码/风控 pause 停止本轮等待用户处理；
 * - popup 手动暂停立即停止拉取并调用 batch-pause；
 * - 队列运行期间的网页 wake 只入队，绝不并行启动第二个执行；
 * - popup 状态不泄漏 Token/招呼语。
 */
const assert = require("node:assert/strict");
const test = require("node:test");

const harness = require("./cloud-test-harness.cjs");
const {
  TEST_TASK_ID,
  OTHER_TASK_ID,
  TOKEN_SENTINEL,
  GREETING_SENTINEL,
  LEASE_SENTINEL,
  loadCloudBackground,
  boundState,
  fetchSuccessChain,
  jsonResponse,
  envelope,
  waitUntil,
  safeString
} = harness;

function fetchByPattern(calls, pattern) {
  return calls.filter((call) => pattern.test(call.url));
}

function popupMessage(h, type) {
  return h.dispatchRuntimeMessage({ source: "GET_JOBS_POPUP", type });
}

function readPauseState(h) {
  return h.storage["__GET_JOBS_CLOUD_PAUSE_STATE__"];
}

test("popup start runs the execution queue: pending polled and tasks executed", async () => {
  let pendingCount = 0;
  let successCount = 0;
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        pendingCount += 1;
        // 重复拉取返回已发放但未开始的任务；成功后再拉取为空。
        if (successCount === 0) {
          return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
        }
        return jsonResponse(envelope({ items: [], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/success$/.test(call.url)) {
        successCount += 1;
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCESS", finishedAt: new Date().toISOString(), version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });

  const started = await popupMessage(h, "CLOUD_EXECUTION_START");
  assert.equal(started.success, true);
  const delivered = await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  assert.ok(delivered, "队列应执行已领取任务");
  await waitUntil(() => pendingCount >= 3);
  assert.ok(pendingCount >= 3, "空轮询后继续按退避轮询");
  const pauseState = readPauseState(h);
  assert.equal(pauseState.paused, false);
  const status = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(status.success, true);
  assert.equal(status.paused, false);
  assert.equal(status.recentCounts.success, 1, "成功计数");
  assert.equal(status.consecutiveFailures, 0, "成功已重置失败计数");
});

test("consecutive failures reach the threshold, call batch-pause and stop the run", async () => {
  const taskIds = [TEST_TASK_ID, OTHER_TASK_ID, "cccccccc-1111-2222-3333-444444444444"];
  const finished = new Set();
  const batchPauseBodies = [];
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: { success: false, failureType: "NETWORK_ERROR" },
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        const items = taskIds
          .filter((id) => !finished.has(id))
          .map((id, index) => ({ ...harness.defaultPendingItem(), id, externalJobId: `ext-${index}` }));
        return jsonResponse(envelope({ items, pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/fail$/.test(call.url)) {
        const id = taskIds.find((entry) => call.url.includes(entry));
        finished.add(id);
        return jsonResponse(envelope({ id, status: "FAILED", finishedAt: new Date().toISOString(), version: 5 }));
      }
      if (/\/batch-pause$/.test(call.url)) {
        batchPauseBodies.push(JSON.parse(call.options.body));
        return jsonResponse(envelope({ pausedCount: 1, remainingRunningCount: 0, pausedTaskIds: [TEST_TASK_ID] }));
      }
      return jsonResponse(envelope({}));
    }
  });

  const started = await popupMessage(h, "CLOUD_EXECUTION_START");
  assert.equal(started.success, true);
  const paused = await waitUntil(() => batchPauseBodies.length > 0);
  assert.ok(paused, "连续失败达到阈值必须调用 batch-pause");
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(fetchByPattern(h.fetchCalls, /\/fail$/).length, 3, "三次失败后停止");
  assert.equal(batchPauseBodies.length, 1, "batch-pause 只调用一次");
  assert.equal(batchPauseBodies[0].reason, "FAILURE_THRESHOLD");
  const state = readPauseState(h);
  assert.equal(state.paused, true, "阈值暂停必须持久化");
  assert.equal(state.reason, "FAILURE_THRESHOLD");
  const status = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(status.paused, true);
  assert.equal(status.consecutiveFailures, 3);
});

test("success resets the consecutive failure counter", async () => {
  const storage = {
    "__GET_JOBS_CLOUD_PAUSE_STATE__": { paused: false, reason: "", consecutiveFailures: 1, updatedAt: new Date().toISOString() }
  };
  let successCount = 0;
  const h = loadCloudBackground({
    storage,
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({
          items: successCount === 0 ? [harness.defaultPendingItem()] : [],
          pollAfterSeconds: 1,
          serverTime: new Date().toISOString()
        }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/success$/.test(call.url)) {
        successCount += 1;
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCESS", finishedAt: new Date().toISOString(), version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });
  const started = await popupMessage(h, "CLOUD_EXECUTION_START");
  assert.equal(started.success, true);
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  await waitUntil(() => {
    const state = readPauseState(h);
    return state && state.consecutiveFailures === 0;
  });
  const state = readPauseState(h);
  assert.equal(state.consecutiveFailures, 0, "成功必须重置失败计数");
  assert.equal(state.paused, false);
});

test("a pause code (login/captcha/risk) stops the run and persists user-action pause", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: { success: false, failureType: "LOGIN_REQUIRED" },
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/pause$/.test(call.url)) {
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "PAUSED_NEED_USER", pauseReason: "LOGIN_REQUIRED", userActionRequired: true, leaseReleased: true, version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });
  const started = await popupMessage(h, "CLOUD_EXECUTION_START");
  assert.equal(started.success, true);
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/pause$/).length > 0);
  await waitUntil(() => {
    const state = readPauseState(h);
    return state && state.paused === true;
  });
  const state = readPauseState(h);
  assert.equal(state.reason, "USER_ACTION_REQUIRED", "登录失效需用户在平台页面处理");
  assert.equal(fetchByPattern(h.fetchCalls, /\/batch-pause$/).length, 0, "单任务 pause 不触发批量暂停");
});

test("popup pause stops pulling, calls batch-pause and persists the pause until resume", async () => {
  let batchPauseBody = null;
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (/\/batch-pause$/.test(call.url)) {
        batchPauseBody = JSON.parse(call.options.body);
        return jsonResponse(envelope({ pausedCount: 0, remainingRunningCount: 0, pausedTaskIds: [] }));
      }
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      return jsonResponse(envelope({}));
    }
  });
  await popupMessage(h, "CLOUD_EXECUTION_START");
  const paused = await popupMessage(h, "CLOUD_EXECUTION_PAUSE");
  assert.equal(paused.success, true);
  assert.equal(batchPauseBody.reason, "USER_REQUESTED");
  const state = readPauseState(h);
  assert.equal(state.paused, true);
  const pendingBefore = fetchByPattern(h.fetchCalls, /\/pending/).length;
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(fetchByPattern(h.fetchCalls, /\/pending/).length, pendingBefore, "暂停后不得继续轮询");

  const resumed = await popupMessage(h, "CLOUD_EXECUTION_START");
  assert.equal(resumed.success, true);
  assert.equal(readPauseState(h).paused, false, "恢复后清除暂停标记");
  // 结束本轮，避免测试结束后循环继续轮询。
  await popupMessage(h, "CLOUD_EXECUTION_PAUSE");
  assert.equal(readPauseState(h).paused, true);
});

test("wake while the execution queue is running is queued, never a second parallel run", async () => {
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      return jsonResponse(envelope({}));
    }
  });
  await popupMessage(h, "CLOUD_EXECUTION_START");
  const response = await h.wake(TEST_TASK_ID, "req-queue");
  assert.equal(response.accepted, true);
  assert.equal(response.state, "queued");
  assert.equal(response.code, "ACCEPTED");
});

test("popup status never leaks token material", async () => {
  const h = loadCloudBackground({ bound: boundState(), fetchImpl: fetchSuccessChain() });
  const status = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(status.success, true);
  const text = safeString(status);
  assert.ok(!text.includes(TOKEN_SENTINEL), "状态不得包含 Token");
  assert.ok(!text.includes(GREETING_SENTINEL), "状态不得包含招呼语");
  assert.equal(status.bound, true);
  assert.equal(status.threshold, 3);
});

test("status exposes queue and running counts without sensitive material", async () => {
  let resolveContent;
  let successCount = 0;
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: new Promise((resolve) => { resolveContent = resolve; }),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        // 成功后再拉取为空：避免模拟后端无限重复下发同一任务。
        return jsonResponse(envelope({
          items: successCount === 0 ? [harness.defaultPendingItem()] : [],
          pollAfterSeconds: 1,
          serverTime: new Date().toISOString()
        }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/success$/.test(call.url)) {
        successCount += 1;
        return jsonResponse(envelope({ id: TEST_TASK_ID, status: "SUCCESS", finishedAt: new Date().toISOString(), version: 5 }));
      }
      return jsonResponse(envelope({}));
    }
  });

  await popupMessage(h, "CLOUD_EXECUTION_START");
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/start$/).length > 0);
  const during = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(during.success, true);
  assert.equal(during.currentTaskCount, 1, "pending 拉取后尚未完成的任务数");
  assert.equal(during.runningCount, 1, "执行中任务数");
  const text = safeString(during);
  assert.ok(!text.includes(TOKEN_SENTINEL), "状态不得包含 Token");
  assert.ok(!text.includes(GREETING_SENTINEL), "状态不得包含招呼语");
  assert.ok(!text.includes(LEASE_SENTINEL), "状态不得包含租约");

  // 结束本轮后计数归零。
  resolveContent({ success: true, resultCode: "DELIVERED", pageState: "SUCCESS_NOTICE" });
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/success$/).length > 0);
  await popupMessage(h, "CLOUD_EXECUTION_PAUSE");
  const after = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(after.currentTaskCount, 0);
  assert.equal(after.runningCount, 0);
});

test("pause cancels an in-flight content delivery: no finish report and no further pulls", async () => {
  let resolveContent;
  const batchPauseBodies = [];
  let pendingCount = 0;
  const h = loadCloudBackground({
    bound: boundState(),
    contentResult: new Promise((resolve) => { resolveContent = resolve; }),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        pendingCount += 1;
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) return jsonResponse(envelope(harness.defaultStartData()));
      if (/\/batch-pause$/.test(call.url)) {
        batchPauseBodies.push(JSON.parse(call.options.body));
        return jsonResponse(envelope({ pausedCount: 1, remainingRunningCount: 0, pausedTaskIds: [TEST_TASK_ID] }));
      }
      return jsonResponse(envelope({}));
    }
  });

  await popupMessage(h, "CLOUD_EXECUTION_START");
  await waitUntil(() => fetchByPattern(h.fetchCalls, /\/start$/).length > 0);
  // 内容脚本 promise 挂起期间手动暂停。
  const paused = await popupMessage(h, "CLOUD_EXECUTION_PAUSE");
  assert.equal(paused.success, true);
  assert.equal(batchPauseBodies.length, 1, "暂停必须调用 batch-pause");
  assert.equal(batchPauseBodies[0].reason, "USER_REQUESTED");

  // 暂停后内容脚本才回传成功：结果绝不回传云端，也绝不覆盖 batch-pause 状态。
  resolveContent({ success: true, resultCode: "DELIVERED", pageState: "SUCCESS_NOTICE" });
  await new Promise((resolve) => setTimeout(resolve, 80));
  assert.equal(fetchByPattern(h.fetchCalls, /\/success$/).length, 0, "暂停后不得回传成功");
  assert.equal(fetchByPattern(h.fetchCalls, /\/fail$/).length, 0, "暂停后不得回传失败");
  assert.equal(fetchByPattern(h.fetchCalls, /\/pause$/).length, 0, "暂停后不得回传单任务 pause");
  assert.equal(batchPauseBodies.length, 1, "batch-pause 只调用一次");
  const pendingBefore = pendingCount;
  await new Promise((resolve) => setTimeout(resolve, 60));
  assert.equal(pendingCount, pendingBefore, "暂停后不得继续轮询");

  const state = readPauseState(h);
  assert.equal(state.paused, true);
  assert.equal(state.reason, "USER_REQUESTED");
  const status = await popupMessage(h, "CLOUD_EXECUTION_STATUS");
  assert.equal(status.paused, true);
  assert.equal(status.currentTaskCount, 0);
  assert.equal(status.runningCount, 0);
});

test("failed batch-pause keeps the pause state but never drops the pending execution", async () => {
  let resolveStart;
  const h = loadCloudBackground({
    bound: boundState(),
    fetchImpl: async (call) => {
      if (call.url.includes("/pending")) {
        return jsonResponse(envelope({ items: [harness.defaultPendingItem()], pollAfterSeconds: 1, serverTime: new Date().toISOString() }));
      }
      if (/\/start$/.test(call.url)) {
        return await new Promise((resolve) => { resolveStart = resolve; });
      }
      if (/\/batch-pause$/.test(call.url)) {
        return jsonResponse(envelope({ success: false, error: { code: "SERVICE_UNAVAILABLE", message: "服务暂不可用", retryable: true }, requestId: "req_test" }), 503);
      }
      return jsonResponse(envelope({}));
    }
  });

  await popupMessage(h, "CLOUD_EXECUTION_START");
  // 任务进入 start 请求（挂起）时手动暂停：batch-pause 失败。
  await waitUntil(() => Boolean(resolveStart));
  const paused = await popupMessage(h, "CLOUD_EXECUTION_PAUSE");
  assert.equal(paused.success, false, "batch-pause 失败必须如实报告");
  const state = readPauseState(h);
  assert.equal(state.paused, true, "暂停状态仍持久化");
  assert.equal(state.reason, "USER_REQUESTED");
  // batch-pause 失败绝不丢弃已持久化的执行状态，用户可稍后恢复/重试。
  assert.ok(h.storage["__GET_JOBS_CLOUD_EXECUTION__"], "执行状态不得被清理");
  // 取消标志使挂起的 start 完成后立即中止，绝不继续导航/回传。
  resolveStart(jsonResponse(envelope(harness.defaultStartData())));
  await new Promise((resolve) => setTimeout(resolve, 80));
  assert.equal(fetchByPattern(h.fetchCalls, /\/success$/).length, 0);
  assert.equal(fetchByPattern(h.fetchCalls, /\/fail$/).length, 0);
});
