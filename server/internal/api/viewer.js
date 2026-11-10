"use strict";

(() => {
  const form = document.getElementById("pair-form");
  const deviceInput = document.getElementById("device-id");
  const codeInput = document.getElementById("pair-code");
  const pairButton = document.getElementById("pair-button");
  const controls = document.getElementById("session-controls");
  const sessionInfo = document.getElementById("session-info");
  const deviceInfo = document.getElementById("device-info");
  const message = document.getElementById("viewer-message");
  const refreshScreenButton = document.getElementById("refresh-screen");
  const refreshStatusButton = document.getElementById("refresh-status");
  const revokeButton = document.getElementById("revoke-session");
  const image = document.getElementById("screen-image");
  const placeholder = document.getElementById("screen-placeholder");
  const screenMeta = document.getElementById("screen-meta");

  let token = "";
  let deviceID = "";
  let imageURL = "";
  let activeRequest = null;
  let generation = 0;
  let busy = false;

  function say(value, error = false) {
    message.textContent = value;
    message.classList.toggle("error", error);
  }

  function setBusy(value) {
    busy = value;
    pairButton.disabled = value;
    refreshScreenButton.disabled = value;
    refreshStatusButton.disabled = value;
  }

  function clearImage() {
    image.hidden = true;
    image.removeAttribute("src");
    placeholder.hidden = false;
    placeholder.textContent = "配对后显示手机画面";
    if (imageURL) URL.revokeObjectURL(imageURL);
    imageURL = "";
    screenMeta.textContent = "配对后获取首张截图，此后只在点击“刷新画面”时更新。";
  }

  function clearSession() {
    generation += 1;
    if (activeRequest) activeRequest.abort();
    activeRequest = null;
    token = "";
    deviceID = "";
    codeInput.value = "";
    form.hidden = false;
    controls.hidden = true;
    deviceInfo.textContent = "";
    sessionInfo.textContent = "";
    clearImage();
    setBusy(false);
  }

  function beginRequest() {
    if (busy) return null;
    setBusy(true);
    activeRequest = new AbortController();
    return { controller: activeRequest, generation };
  }

  function endRequest(current) {
    if (current.generation === generation && activeRequest === current.controller) {
      activeRequest = null;
      setBusy(false);
    }
  }

  async function request(path, options, current, bearer = token) {
    const headers = {};
    if (bearer) headers.Authorization = `Bearer ${bearer}`;
    if (options.body) headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
      ...options,
      headers,
      signal: current.controller.signal,
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      referrerPolicy: "no-referrer"
    });
    if (!response.ok) {
      let detail = "";
      try {
        const body = await response.json();
        detail = body.error?.message || "";
      } catch (_) { /* A non-JSON error still has a useful HTTP status. */ }
      const error = new Error(detail || `HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return response;
  }

  function failed(error, prefix) {
    if (error.name === "AbortError") return;
    if (error.status === 401 && token) {
      clearSession();
      say("授权已失效，请在手机重新生成口令。", true);
      return;
    }
    say(`${prefix}：${error.message}`, true);
  }

  function displayStatus(state) {
    const reachability = state.online ? (state.paused ? "已暂停" : "在线") : "离线";
    const lastSeen = state.last_seen_at ? new Date(state.last_seen_at).toLocaleString("zh-CN") : "暂无";
    deviceInfo.textContent = `设备 ${reachability} · 最近响应 ${lastSeen}`;
    if (!state.online || state.paused) clearImage();
  }

  async function loadStatus(current) {
    const response = await request(`/api/v1/devices/${encodeURIComponent(deviceID)}/status`, {}, current);
    const state = await response.json();
    if (current.generation !== generation) return null;
    displayStatus(state);
    return state;
  }

  async function loadScreen(current) {
    clearImage();
    placeholder.textContent = "正在获取当前画面…";
    const response = await request(`/api/v1/devices/${encodeURIComponent(deviceID)}/screenshot`, {}, current);
    const blob = await response.blob();
    if (current.generation !== generation) return;
    if (blob.type !== "image/jpeg" || blob.size === 0) throw new Error("服务器未返回 JPEG 截图");
    imageURL = URL.createObjectURL(blob);
    image.src = imageURL;
    try {
      await image.decode();
    } catch (_) {
      if (current.generation !== generation) return;
      clearImage();
      throw new Error("截图无法解码");
    }
    if (current.generation !== generation) return;
    image.hidden = false;
    placeholder.hidden = true;
    const capturedAt = response.headers.get("X-Captured-At");
    const captured = capturedAt ? new Date(capturedAt).toLocaleString("zh-CN") : "时间未知";
    const width = response.headers.get("X-Screen-Width") || "?";
    const height = response.headers.get("X-Screen-Height") || "?";
    screenMeta.textContent = `采集于 ${captured} · 原始尺寸 ${width} × ${height}`;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const current = beginRequest();
    if (!current) return;
    const id = deviceInput.value.trim();
    const code = codeInput.value;
    say("正在向手机确认口令…");
    try {
      const response = await request("/api/v1/auth/pair", {
        method: "POST", body: JSON.stringify({ device_id: id, code })
      }, current, "");
      const session = await response.json();
      if (current.generation !== generation) return;
      token = session.access_token;
      deviceID = session.device_id;
      codeInput.value = "";
      form.hidden = true;
      controls.hidden = false;
      const expiry = new Date(session.expires_at).toLocaleString("zh-CN");
      sessionInfo.textContent = `已配对 · 手机授权 ${session.scope === "controller" ? "控制" : "只读"} · 此网页仅查看 · 最迟 ${expiry} 失效`;
      const state = await loadStatus(current);
      if (state?.screen_capture_available) {
        await loadScreen(current);
        if (current.generation !== generation) return;
        say("已取得当前画面；需要新画面时请手动刷新。");
      } else {
        say("配对成功，手机目前离线或暂停。", true);
      }
    } catch (error) {
      if (current.generation !== generation) return;
      failed(error, "连接失败");
    } finally {
      endRequest(current);
    }
  });

  refreshStatusButton.addEventListener("click", async () => {
    const current = beginRequest();
    if (!current) return;
    try {
      await loadStatus(current);
      if (current.generation !== generation) return;
      say("状态已更新。");
    } catch (error) {
      if (current.generation !== generation) return;
      failed(error, "查询失败");
    } finally {
      endRequest(current);
    }
  });

  refreshScreenButton.addEventListener("click", async () => {
    const current = beginRequest();
    if (!current) return;
    try {
      await loadScreen(current);
      if (current.generation !== generation) return;
      say("已取得当前画面。");
    } catch (error) {
      if (current.generation !== generation) return;
      clearImage();
      placeholder.textContent = "当前画面不可用";
      failed(error, "截图失败");
    } finally {
      endRequest(current);
    }
  });

  revokeButton.addEventListener("click", async () => {
    const oldToken = token;
    clearSession();
    const revokeGeneration = generation;
    say("正在撤销服务器授权…");
    try {
      const response = await fetch("/api/v1/auth/revoke", {
        method: "POST",
        headers: { Authorization: `Bearer ${oldToken}` },
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
        referrerPolicy: "no-referrer"
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      if (revokeGeneration === generation) say("授权已撤销。需要再次查看时，请在手机生成新口令。");
    } catch (_) {
      if (revokeGeneration === generation) say("本地令牌已清除；服务器撤销未确认，请在手机端点击“立即撤销 HTTPS 授权”。", true);
    }
  });

  window.addEventListener("pagehide", clearSession);
})();
