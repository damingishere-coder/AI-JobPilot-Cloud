(function () {
  "use strict";

  const Cloud = globalThis.GetJobsCloudClient;
  if (!Cloud) return;

  const INSTALLATION_KEY = "__GET_JOBS_CLOUD_INSTALLATION_ID__";

  const elements = {
    bindSection: document.getElementById("bind-section"),
    statusSection: document.getElementById("status-section"),
    apiBaseSelect: document.getElementById("api-base-select"),
    apiBaseCustomField: document.getElementById("api-base-custom-field"),
    apiBaseInput: document.getElementById("api-base-input"),
    apiBaseDisplay: document.getElementById("api-base-display"),
    bindCodeInput: document.getElementById("bind-code-input"),
    deviceNameInput: document.getElementById("device-name-input"),
    bindButton: document.getElementById("bind-button"),
    unbindButton: document.getElementById("unbind-button"),
    captureCurrentButton: document.getElementById("capture-current-button"),
    captureBatchButton: document.getElementById("capture-batch-button"),
    captureQueueCount: document.getElementById("capture-queue-count"),
    statusDot: document.getElementById("status-dot"),
    statusText: document.getElementById("status-text"),
    deviceName: document.getElementById("device-name"),
    deviceBrowser: document.getElementById("device-browser"),
    deviceExtension: document.getElementById("device-extension"),
    tokenExpires: document.getElementById("token-expires"),
    executionDot: document.getElementById("execution-dot"),
    executionStatusText: document.getElementById("execution-status-text"),
    executionFailures: document.getElementById("execution-failures"),
    executionSummary: document.getElementById("execution-summary"),
    executionStartButton: document.getElementById("execution-start-button"),
    executionPauseButton: document.getElementById("execution-pause-button"),
    message: document.getElementById("message")
  };

  let bound = null;
  let busy = false;

  init();

  async function init() {
    renderApiBaseSelect();
    bound = await Cloud.readBoundState();
    if (bound) {
      await showBoundStatus();
      await refreshMe();
      await refreshQueueCount();
    } else {
      showBindForm();
    }
  }

  function renderApiBaseSelect() {
    const select = elements.apiBaseSelect;
    select.textContent = "";
    for (const base of Cloud.allowedApiBases()) {
      const option = document.createElement("option");
      option.value = base;
      option.textContent = base;
      select.appendChild(option);
    }
    const custom = document.createElement("option");
    custom.value = Cloud.CUSTOM_API_BASE_VALUE;
    custom.textContent = "自定义地址…";
    select.appendChild(custom);
    const current = bound?.apiBase;
    if (current && !Cloud.allowedApiBases().includes(current)) {
      select.value = Cloud.CUSTOM_API_BASE_VALUE;
      elements.apiBaseInput.value = current;
      toggleCustomApiBaseField(true);
    } else {
      select.value = current && Cloud.isAllowedApiBase(current)
        ? current
        : Cloud.defaultApiBase();
      toggleCustomApiBaseField(false);
    }
  }

  function toggleCustomApiBaseField(show) {
    elements.apiBaseCustomField.classList.toggle("hidden", !show);
  }

  /** 绑定地址：下拉选择预设，或选择「自定义地址…」后填写文本框。 */
  function resolveApiBaseInput() {
    if (elements.apiBaseSelect.value === Cloud.CUSTOM_API_BASE_VALUE) {
      return String(elements.apiBaseInput.value || "").trim();
    }
    return String(elements.apiBaseSelect.value || "");
  }

  function showBindForm() {
    elements.bindSection.classList.remove("hidden");
    elements.statusSection.classList.add("hidden");
    elements.bindButton.disabled = false;
  }

  async function showBoundStatus() {
    const device = bound?.device || {};
    elements.bindSection.classList.add("hidden");
    elements.statusSection.classList.remove("hidden");
    elements.statusDot.className = "dot ok";
    elements.statusText.textContent = "已连接云端";
    elements.apiBaseDisplay.textContent = bound?.apiBase || "未知";
    elements.deviceName.textContent = device.deviceName || "未命名设备";
    elements.deviceBrowser.textContent = [device.browserName, device.browserVersion]
      .filter(Boolean).join(" ") || "未知";
    elements.deviceExtension.textContent = device.extensionVersion || "未知";
    elements.tokenExpires.textContent = formatExpires(bound.tokenExpiresAt);
  }

  function formatExpires(value) {
    if (!value) return "未知";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "未知" : date.toLocaleString();
  }

  async function refreshMe() {
    if (!bound) return;
    const result = await Cloud.fetchMe(bound.apiBase, bound.token);
    if (result.success && result.data) {
      const device = result.data.device || {};
      bound = {
        ...bound,
        device: {
          ...(bound.device || {}),
          id: device.id ?? (bound.device?.id || ""),
          deviceName: device.deviceName ?? (bound.device?.deviceName || ""),
          browserName: device.browserName ?? (bound.device?.browserName || ""),
          browserVersion: device.browserVersion ?? (bound.device?.browserVersion || ""),
          extensionVersion: device.extensionVersion ?? (bound.device?.extensionVersion || ""),
          status: device.status ?? (bound.device?.status || ""),
          capabilities: device.capabilities ?? (bound.device?.capabilities || []),
          boundAt: device.boundAt ?? (bound.device?.boundAt || "")
        },
        tokenExpiresAt: result.data.token?.expiresAt || bound.tokenExpiresAt
      };
      await Cloud.writeBoundState(bound);
      await showBoundStatus();
      if (result.data.device?.status === "REVOKED") {
        showMessage("该设备已被服务端撤销，请重新绑定。", "info");
      }
      return;
    }
    if (result.code && Cloud.shouldClearTokenOnCode(result.code)) {
      await Cloud.clearBoundState();
      bound = null;
      showBindForm();
      showMessage(`云端凭证失效（${result.code}），请重新绑定。`, "error");
      return;
    }
    showMessage(result.message || "无法获取设备状态", "error");
  }

  function showMessage(text, kind) {
    elements.message.textContent = text || "";
    elements.message.className = `message ${kind || "info"}${text ? "" : " hidden"}`;
  }

  async function handleBind() {
    if (busy) return;
    busy = true;
    elements.bindButton.disabled = true;
    showMessage("", "info");
    try {
      const bindCode = String(elements.bindCodeInput.value || "").trim().toUpperCase();
      const deviceName = String(elements.deviceNameInput.value || "").trim();
      if (!/^[A-Za-z0-9]{5}-[A-Za-z0-9]{5}$/.test(bindCode)) {
        showMessage("绑定码格式应为 XXXXX-XXXXX。", "error");
        return;
      }
      if (!deviceName || deviceName.length > 100) {
        showMessage("请填写 1-100 字的设备显示名。", "error");
        return;
      }
      const apiBase = Cloud.normalizeApiBase(resolveApiBaseInput());
      if (!apiBase) {
        showMessage("云端 API 地址无效：远程仅支持 https 完整地址，本地仅支持 localhost/127.0.0.1 且必须带端口。", "error");
        return;
      }
      // 非静态批准的本地入口（例如远程 https origin 或自定义本地端口）：
      // 绑定前请求精确 `${origin}/*` host 权限，用户拒绝则 fail-closed，
      // 不发送绑定请求、不保存任何 Token。
      if (!(await Cloud.ensureHostPermission(apiBase))) {
        showMessage("未获得该云端地址的访问授权，绑定已取消。", "error");
        return;
      }

      const installationId = await getOrCreateInstallationId();
      const browser = browserInfo();
      const result = await Cloud.bindDevice(apiBase, {
        bindCode,
        installationId,
        deviceName,
        browserName: browser.name,
        browserVersion: browser.version,
        extensionVersion: Cloud.currentExtensionVersion() || "1.5.0",
        capabilities: ["BOSS", "ZHILIAN"]
      });

      if (!result.success || !result.data) {
        showMessage(`绑定失败：${result.code || "UNKNOWN"} ${result.message || ""}`.trim(), "error");
        return;
      }

      const token = result.data.token?.value;
      if (typeof token !== "string" || !token) {
        showMessage("绑定响应缺少凭证，未保存任何本地状态。", "error");
        return;
      }
      const device = result.data.device || {};
      bound = {
        apiBase,
        installationId,
        token,
        tokenExpiresAt: result.data.token.expiresAt || "",
        device: {
          id: device.id || "",
          deviceName: device.deviceName || deviceName,
          browserName: device.browserName || browser.name,
          browserVersion: device.browserVersion || browser.version,
          extensionVersion: device.extensionVersion || Cloud.currentExtensionVersion() || "1.5.0",
          status: device.status || "ACTIVE",
          capabilities: device.capabilities || ["BOSS", "ZHILIAN"],
          boundAt: device.boundAt || new Date().toISOString()
        }
      };
      const saved = await Cloud.writeBoundState(bound);
      if (!saved) {
        showMessage("本地保存失败，已放弃本次绑定结果。", "error");
        bound = null;
        return;
      }
      elements.bindCodeInput.value = "";
      await showBoundStatus();
      showMessage("绑定成功，已可接收网页确认后的投递唤醒。", "ok");
    } catch {
      showMessage("绑定过程中出现异常，请重试。", "error");
    } finally {
      busy = false;
      elements.bindButton.disabled = false;
    }
  }

  async function getOrCreateInstallationId() {
    let installationId = bound?.installationId || "";
    if (/^[A-Za-z0-9_-]{16,128}$/.test(installationId)) return installationId;
    try {
      const stored = await chrome.storage.local.get(INSTALLATION_KEY);
      installationId = stored?.[INSTALLATION_KEY] || "";
    } catch {
      installationId = "";
    }
    if (/^[A-Za-z0-9_-]{16,128}$/.test(installationId)) return installationId;
    installationId = Cloud.randomInstallationId();
    try {
      await chrome.storage.local.set({ [INSTALLATION_KEY]: installationId });
    } catch {
      // 安装 ID 生成后无法持久化时仅本次使用，不阻断绑定。
    }
    return installationId;
  }

  /** 从扩展环境的非敏感浏览器信息得到名称/版本，不使用任何硬件指纹。 */
  function browserInfo() {
    const ua = String(globalThis.navigator?.userAgent || "");
    const name = /Edg\//.test(ua) ? "Edge" : "Chrome";
    const match = ua.match(/(?:Edg|Chrome)\/(\d+)/);
    return { name, version: match ? match[1] : "" };
  }

  async function handleUnbind() {
    if (busy) return;
    busy = true;
    try {
      await Cloud.unbindLocal();
      bound = null;
      showBindForm();
      showMessage("已解除本机绑定。若需服务端撤销设备，请在网页后台设备管理中操作。", "info");
    } catch {
      showMessage("解除绑定时出现异常，请重试。", "error");
    } finally {
      busy = false;
    }
  }

  // ---------------------------------------------------------------- capture

  async function refreshQueueCount() {
    const queue = await Cloud.readCaptureQueue();
    const count = queue.length;
    elements.captureQueueCount.textContent = String(count);
    elements.captureQueueCount.classList.toggle("hidden", count === 0);
    elements.captureBatchButton.disabled = count === 0;
  }

  /** 采集当前平台页面：background 自动识别活动标签页平台，转发内容脚本并回传清洗后的白名单负载。 */
  async function collectCurrentPage() {
    return await chrome.runtime.sendMessage({
      source: "GET_JOBS_POPUP",
      type: "CLOUD_CAPTURE_COLLECT"
    });
  }

  async function handleCaptureCurrent() {
    if (busy || !bound) return;
    busy = true;
    elements.captureCurrentButton.disabled = true;
    showMessage("正在采集当前页面岗位…", "info");
    try {
      const collected = await collectCurrentPage();
      if (!collected?.success || !collected.payload) {
        showMessage(collected?.message || "采集失败，请确认已打开岗位详情页", "error");
        return;
      }
      const result = await Cloud.captureJob(bound.apiBase, bound.token, collected.payload);
      if (!result.success) {
        if (result.code && Cloud.shouldClearTokenOnCode(result.code)) {
          await Cloud.clearBoundState();
          bound = null;
          showBindForm();
          showMessage(`云端凭证失效（${result.code}），请重新绑定。`, "error");
          return;
        }
        if (Cloud.isRetryableCloudFailure(result)) {
          // 网络/限流/服务端暂时不可用：离线暂存，稍后由批量上传重试。
          await Cloud.enqueueCapture(collected.payload);
          await refreshQueueCount();
          showMessage(`云端暂不可用（${result.code || "NETWORK_ERROR"}），岗位已加入本地采集队列，可稍后重试。`, "error");
          return;
        }
        showMessage(`上传失败：${result.code || "UNKNOWN"} ${result.message || ""}`.trim(), "error");
        return;
      }
      // 上传成功不重复入队：同一岗位绝不再次上传（服务端幂等，但避免无谓请求）。
      const status = result.data?.status === "created" ? "已新增" : "已存在，未重复入库";
      showMessage(`上传成功（${status}）。`, "ok");
    } catch {
      showMessage("上传过程中出现异常，请重试。", "error");
    } finally {
      busy = false;
      elements.captureCurrentButton.disabled = false;
    }
  }

  async function handleCaptureBatch() {
    if (busy || !bound) return;
    busy = true;
    elements.captureBatchButton.disabled = true;
    showMessage("正在逐条上传已采集岗位…", "info");
    try {
      const queue = await Cloud.readCaptureQueue();
      if (!queue.length) {
        showMessage("本地采集队列为空，请先在岗位页面上采集。", "info");
        return;
      }
      let uploaded = 0;
      let duplicates = 0;
      let failed = 0;
      let failedAttempts = 0;
      const uploadedItems = [];
      const failedItems = [];
      // 逐条上传：服务端返回 created/duplicate 的条目移出队列；失败条目保留
      // 并记录重试次数供用户展示/重试。单条失败或重试耗尽不影响其它条目，
      // 已成功上传的岗位绝不会再次上传。
      for (const item of queue) {
        const payload = Cloud.projectCapturePayload(item);
        if (!payload) {
          // 本地队列数据畸形（缺必填字段），永远无法上传：移出并计失败。
          failed += 1;
          uploadedItems.push(item);
          continue;
        }
        const result = await Cloud.uploadCaptureItemWithRetry(
          bound.apiBase, bound.token, payload, { maxAttempts: 3 }
        );
        if (result.success) {
          if (result.data?.status === "created") {
            uploaded += 1;
          } else {
            duplicates += 1;
          }
          uploadedItems.push(item);
          continue;
        }
        if (result.code && Cloud.shouldClearTokenOnCode(result.code)) {
          // 已成功上传的条目仍然移出队列，避免下次重复上传。
          if (uploadedItems.length) {
            await Cloud.removeCaptureQueueItems(uploadedItems);
          }
          await Cloud.clearBoundState();
          bound = null;
          showBindForm();
          await refreshQueueCount();
          showMessage(`云端凭证失效（${result.code}），请重新绑定。`, "error");
          return;
        }
        failed += 1;
        failedAttempts += result.attempts || 1;
        failedItems.push({ item, attempts: result.attempts || 1 });
      }
      if (uploadedItems.length) {
        await Cloud.removeCaptureQueueItems(uploadedItems);
      }
      for (const entry of failedItems) {
        await Cloud.markCaptureItemAttempts(entry.item, entry.attempts);
      }
      await refreshQueueCount();
      const retryNote = failedItems.length
        ? `，失败 ${failedAttempts} 条次已记录在队列中，修正后可再次点击重试`
        : "";
      showMessage(
        `上传完成：新增 ${uploaded} 条，已存在 ${duplicates} 条，失败 ${failed} 条${retryNote}。`,
        failed > 0 ? "error" : "ok"
      );
    } catch {
      showMessage("批量上传过程中出现异常，请重试。", "error");
    } finally {
      busy = false;
      await refreshQueueCount();
    }
  }

  // ---------------------------------------------------------------- execution

  /** 向 background 发送固定执行控制消息；响应只消费稳定字段，不透出原始文本。 */
  async function sendExecutionControl(type) {
    if (!Cloud.CLOUD_CONTROL_MESSAGE_TYPES.includes(type)) return null;
    try {
      return await chrome.runtime.sendMessage({ source: "GET_JOBS_POPUP", type });
    } catch {
      return null;
    }
  }

  async function refreshExecutionStatus() {
    const status = await sendExecutionControl("CLOUD_EXECUTION_STATUS");
    if (!status || status.success !== true) {
      elements.executionDot.classList.add("hidden");
      elements.executionStatusText.textContent = "执行队列：状态未知";
      elements.executionSummary.textContent = "最近结果：—";
      elements.executionStartButton.disabled = !bound;
      elements.executionPauseButton.disabled = true;
      return;
    }
    const running = Boolean(status.loopRunning);
    const paused = Boolean(status.paused);
    const counts = status.recentCounts || {};
    elements.executionDot.classList.remove("hidden", "ok", "warn", "off");
    elements.executionDot.classList.add(running ? "ok" : paused ? "warn" : "off");
    if (running) {
      elements.executionStatusText.textContent = "执行队列：运行中";
    } else if (paused) {
      const reasonText = status.pauseReason === "FAILURE_THRESHOLD"
        ? "连续失败达到阈值"
        : status.pauseReason === "USER_ACTION_REQUIRED"
          ? "需在招聘平台页面处理"
          : "已手动暂停";
      elements.executionStatusText.textContent = `执行队列：已暂停（${reasonText}）`;
    } else {
      elements.executionStatusText.textContent = "执行队列：空闲";
    }
    const failures = Number(status.consecutiveFailures) || 0;
    elements.executionFailures.textContent = failures > 0 ? `连续失败 ${failures}/${status.threshold || 3}` : "";
    elements.executionFailures.classList.toggle("hidden", failures === 0);
    elements.executionSummary.textContent = `当前队列 ${status.currentTaskCount || 0} 个 · 执行中 ${status.runningCount || 0} · 成功 ${counts.success || 0} · 失败 ${counts.failed || 0} · 需用户处理 ${counts.pausedNeedUser || 0}`;
    elements.executionStartButton.disabled = !bound || running;
    elements.executionPauseButton.disabled = !bound || !running;
  }

  async function handleExecutionStart() {
    if (busy || !bound) return;
    busy = true;
    elements.executionStartButton.disabled = true;
    showMessage("", "info");
    try {
      const result = await sendExecutionControl("CLOUD_EXECUTION_START");
      if (result?.success) {
        showMessage(result.message || "已开始执行队列，popup 可关闭，执行在后台继续。", "ok");
      } else {
        showMessage(result?.message || "开始执行失败", "error");
      }
    } finally {
      busy = false;
      await refreshExecutionStatus();
    }
  }

  async function handleExecutionPause() {
    if (busy || !bound) return;
    busy = true;
    elements.executionPauseButton.disabled = true;
    showMessage("", "info");
    try {
      const result = await sendExecutionControl("CLOUD_EXECUTION_PAUSE");
      if (result?.success) {
        const count = Number(result.pausedCount) || 0;
        showMessage(`已暂停执行队列${count > 0 ? `，${count} 个执行中任务已安全转「需用户处理」` : ""}。`, "ok");
      } else {
        showMessage(result?.message || "暂停执行失败", "error");
      }
    } finally {
      busy = false;
      await refreshExecutionStatus();
    }
  }

  elements.bindButton.addEventListener("click", handleBind);
  elements.unbindButton.addEventListener("click", handleUnbind);
  elements.captureCurrentButton.addEventListener("click", handleCaptureCurrent);
  elements.captureBatchButton.addEventListener("click", handleCaptureBatch);
  elements.executionStartButton.addEventListener("click", handleExecutionStart);
  elements.executionPauseButton.addEventListener("click", handleExecutionPause);
  elements.apiBaseSelect.addEventListener("change", () => {
    toggleCustomApiBaseField(elements.apiBaseSelect.value === Cloud.CUSTOM_API_BASE_VALUE);
  });

  /** 未绑定状态下的执行控制区只读展示，按钮禁用。 */
  function renderUnboundExecution() {
    elements.executionDot.classList.add("hidden");
    elements.executionStatusText.textContent = "执行队列：未绑定";
    elements.executionSummary.textContent = "最近结果：—";
    elements.executionFailures.classList.add("hidden");
    elements.executionStartButton.disabled = true;
    elements.executionPauseButton.disabled = true;
  }

  const originalShowBoundStatus = showBoundStatus;
  showBoundStatus = async () => {
    await originalShowBoundStatus();
    await refreshExecutionStatus();
  };
  const originalShowBindForm = showBindForm;
  showBindForm = () => {
    originalShowBindForm();
    renderUnboundExecution();
  };
  renderUnboundExecution();
})();
