(function () {
  "use strict";

  const Cloud = globalThis.GetJobsCloudClient;
  if (!Cloud) return;

  const INSTALLATION_KEY = "__GET_JOBS_CLOUD_INSTALLATION_ID__";

  const elements = {
    bindSection: document.getElementById("bind-section"),
    statusSection: document.getElementById("status-section"),
    apiBaseSelect: document.getElementById("api-base-select"),
    bindCodeInput: document.getElementById("bind-code-input"),
    deviceNameInput: document.getElementById("device-name-input"),
    bindButton: document.getElementById("bind-button"),
    unbindButton: document.getElementById("unbind-button"),
    statusDot: document.getElementById("status-dot"),
    statusText: document.getElementById("status-text"),
    deviceName: document.getElementById("device-name"),
    deviceBrowser: document.getElementById("device-browser"),
    deviceExtension: document.getElementById("device-extension"),
    tokenExpires: document.getElementById("token-expires"),
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
    select.value = bound?.apiBase && Cloud.isAllowedApiBase(bound.apiBase)
      ? bound.apiBase
      : Cloud.defaultApiBase();
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
      const apiBase = elements.apiBaseSelect.value;
      if (!Cloud.isAllowedApiBase(apiBase)) {
        showMessage("云端 API 地址不在允许范围内。", "error");
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
        extensionVersion: Cloud.currentExtensionVersion() || "1.4.0",
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
          extensionVersion: device.extensionVersion || Cloud.currentExtensionVersion() || "1.4.0",
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

  elements.bindButton.addEventListener("click", handleBind);
  elements.unbindButton.addEventListener("click", handleUnbind);
})();
