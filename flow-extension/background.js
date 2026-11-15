// Service worker: talks to the Media Studio app (localhost), fetches images, and catches Flow's downloads.
// The page automation itself lives in content.js (it survives as long as the Flow tab is open).

const DEFAULT_APP = 'http://localhost:8787';

async function appBase() {
  const { appUrl } = await chrome.storage.local.get('appUrl');
  return (appUrl || DEFAULT_APP).replace(/\/+$/, '');
}

async function api(path, opts = {}) {
  const base = await appBase();
  const res = await fetch(base + path, {
    method: opts.method || 'GET',
    headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!res.ok) throw new Error(data.error || data.message || `HTTP ${res.status}`);
  return data;
}

function toBase64(buf) {
  const bytes = new Uint8Array(buf);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

// ---- downloads started by a click on Flow's "Download" menu ----
let expecting = null; // { resolve, reject, timer, id }

chrome.downloads.onCreated.addListener((item) => {
  if (!expecting || expecting.id != null) return;
  const u = item.finalUrl || item.url || '';
  if (!/flow\.google\.com|labs\.google|googleusercontent|storage\.googleapis|^blob:|^data:/.test(u) && item.byExtensionId !== chrome.runtime.id) return;
  expecting.id = item.id;
});

chrome.downloads.onChanged.addListener(async (delta) => {
  if (!expecting || delta.id !== expecting.id || !delta.state) return;
  const e = expecting;
  if (delta.state.current === 'complete') {
    const [item] = await chrome.downloads.search({ id: delta.id });
    clearTimeout(e.timer);
    expecting = null;
    e.resolve({ id: delta.id, path: item?.filename, name: (item?.filename || '').split(/[\\/]/).pop() });
  } else if (delta.state.current === 'interrupted') {
    clearTimeout(e.timer);
    expecting = null;
    e.reject(new Error('Chrome dừng tải xuống'));
  }
});

function waitDownload(ms) {
  if (expecting) { clearTimeout(expecting.timer); expecting.reject(new Error('bị thay bằng lần tải khác')); }
  return new Promise((resolve, reject) => {
    expecting = { resolve, reject, id: null, timer: setTimeout(() => { expecting = null; reject(new Error('không thấy file tải về')); }, ms) };
  });
}

// ---- trusted input through the DevTools protocol (same mechanism Playwright uses) ----
// Synthetic events from a content script are "untrusted": Flow's editor ignores typed text and the Create button
// stays disabled. chrome.debugger sends real mouse/keyboard input and fills file choosers with files on disk.
const attached = new Map(); // tabId -> { files, fileDone }
const cdp = (tabId, method, params = {}) => chrome.debugger.sendCommand({ tabId }, method, params);

async function cdpAttach(tabId) {
  if (attached.has(tabId)) return;
  try {
    await chrome.debugger.attach({ tabId }, '1.3');
  } catch (e) {
    if (!/already attached/i.test(String(e?.message || e))) throw e;
  }
  attached.set(tabId, { files: null, fileDone: null });
  await cdp(tabId, 'Page.enable');
  await cdp(tabId, 'DOM.enable');
  await cdp(tabId, 'Page.setInterceptFileChooserDialog', { enabled: true });
  // Save Flow's downloads straight into a fixed folder: with Chrome's "Ask where to save each file" turned on,
  // a normal download would stop at the "Save as" dialog and wait for the user.
  try {
    const { downloadDir } = await api('/api/flow-ext/ping');
    if (downloadDir) await cdp(tabId, 'Page.setDownloadBehavior', { behavior: 'allow', downloadPath: downloadDir });
  } catch (e) {
    console.warn('[Media Studio → Flow] could not set the download folder:', e?.message || e);
  }
}

async function cdpDetach(tabId) {
  if (!attached.has(tabId)) return;
  attached.delete(tabId);
  try { await cdp(tabId, 'Page.setDownloadBehavior', { behavior: 'default' }); } catch {}
  try { await chrome.debugger.detach({ tabId }); } catch {}
}

chrome.debugger.onDetach.addListener((src) => attached.delete(src.tabId));
chrome.debugger.onEvent.addListener(async (src, method, params) => {
  if (method !== 'Page.fileChooserOpened') return;
  const st = attached.get(src.tabId);
  if (!st?.files) return; // nothing armed: the dialog is simply swallowed
  const files = st.files, done = st.fileDone;
  st.files = null;
  st.fileDone = null;
  try {
    await cdp(src.tabId, 'DOM.setFileInputFiles', { files, backendNodeId: params.backendNodeId });
    done?.(null);
  } catch (e) {
    done?.(e);
  }
});

async function cdpClick(tabId, x, y) {
  await cdp(tabId, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
  await cdp(tabId, 'Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await cdp(tabId, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}

const KEYS = {
  Escape: { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 },
  Backspace: { key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 },
  a: { key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65 },
  Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
};
async function cdpKey(tabId, name, modifiers = 0) {
  const k = KEYS[name];
  await cdp(tabId, 'Input.dispatchKeyEvent', { type: 'rawKeyDown', modifiers, ...k });
  await cdp(tabId, 'Input.dispatchKeyEvent', { type: 'keyUp', modifiers, ...k });
}

function armFiles(tabId, paths, ms) {
  const st = attached.get(tabId);
  if (!st) throw new Error('chưa gắn trình điều khiển vào tab');
  return {
    wait: new Promise((resolve, reject) => {
      const timer = setTimeout(() => { st.files = null; st.fileDone = null; reject(new Error('Flow không mở hộp chọn tệp')); }, ms);
      st.files = paths;
      st.fileDone = (err) => { clearTimeout(timer); err ? reject(err) : resolve(true); };
    }),
  };
}
const armed = new Map(); // tabId -> pending wait promise

// ---- open Flow by itself when the app queues scenes ----
const FLOW_HOME = 'https://flow.google.com/';
const FLOW_TABS = ['https://flow.google.com/*', 'https://labs.google/*'];

/**
 * Brings up a Flow tab for the current queue and starts it: the project's saved Flow project if there is one,
 * otherwise Flow's home page, where content.js presses "New project". Reuses an open Flow tab when possible.
 */
async function wakeFlow(reason) {
  const s = await api('/api/flow-ext/session');
  if (!s.active) return { started: false, why: 'không có cảnh nào đang chờ' };
  await chrome.storage.local.set({ wokenJob: s.jobId });
  const target = s.flowProjectUrl || FLOW_HOME;
  const tabs = await chrome.tabs.query({ url: FLOW_TABS });
  const onTarget = (t) => (s.flowProjectUrl ? (t.url || '').startsWith(s.flowProjectUrl) : !(t.url || '').includes('/project/'));
  let tab = tabs.find(onTarget) || tabs.find((t) => !(t.url || '').includes('/project/')) || tabs[0];

  if (tab) {
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
    if (onTarget(tab)) {
      try {
        const st = await chrome.tabs.sendMessage(tab.id, { type: 'status' });
        if (st?.running) return { started: true, tab: 'đang chạy' };
        await chrome.tabs.sendMessage(tab.id, { type: 'start' });
        return { started: true, tab: 'có sẵn' };
      } catch { /* page opened before the extension was (re)loaded: reload it below */ }
    }
    await chrome.storage.local.set({ autostart: Date.now() });
    await chrome.tabs.update(tab.id, { url: target });
    return { started: true, tab: 'mở lại' };
  }
  await chrome.storage.local.set({ autostart: Date.now() });
  const win = await chrome.windows.getLastFocused().catch(() => null);
  if (win?.id != null) await chrome.tabs.create({ url: target, windowId: win.id, active: true });
  else await chrome.windows.create({ url: target, focused: true });
  console.log('[Media Studio → Flow] opened Flow for job', s.jobId, reason || '');
  return { started: true, tab: 'mới' };
}

// Fallback for queues made where the page can't reach the extension (the desktop app, another browser):
// check every 30 s and open Flow once per new queue.
chrome.alarms.create('ms-poll', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(async (a) => {
  if (a.name !== 'ms-poll') return;
  try {
    const s = await api('/api/flow-ext/session');
    if (!s.active) return;
    const { wokenJob } = await chrome.storage.local.get('wokenJob');
    if (wokenJob === s.jobId) return;
    await wakeFlow('poll');
  } catch { /* app not running */ }
});

// ---- messages from content.js / popup.js / app-bridge.js ----
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    switch (msg.type) {
      case 'wake':
        // A local app asking for Flow becomes the app the extension talks to (only localhost / 127.0.0.1).
        if (msg.appUrl && /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(msg.appUrl)) {
          await chrome.storage.local.set({ appUrl: msg.appUrl });
        }
        return wakeFlow(msg.reason);
      case 'api':
        return api(msg.path, { method: msg.method, body: msg.body });
      case 'appBase':
        return appBase();
      case 'fetchRef': { // a reference image from the app → base64 for the page's file input
        const base = await appBase();
        const res = await fetch(base + msg.url);
        if (!res.ok) throw new Error(`không tải được ảnh tham chiếu ${msg.name} (HTTP ${res.status})`);
        const buf = await res.arrayBuffer();
        return { b64: toBase64(buf), type: res.headers.get('content-type') || 'image/png' };
      }
      case 'expectDownload': // content.js is about to click "Download": resolve with the saved file path
        return waitDownload(msg.timeoutMs || 30000);
      case 'removeDownload':
        try { await chrome.downloads.removeFile(msg.id); } catch {}
        try { await chrome.downloads.erase({ id: msg.id }); } catch {}
        return true;
      case 'uploadImageUrl': { // fallback: fetch the tile image (cross-origin allowed here) and send it to the app
        const res = await fetch(msg.src, { credentials: 'include' });
        if (!res.ok) throw new Error(`không tải được ảnh từ Flow (HTTP ${res.status})`);
        const blob = await res.blob();
        return postBlob(msg.sceneId, blob, msg.name || 'flow.jpg');
      }
      case 'uploadImageB64': {
        const bin = Uint8Array.from(atob(msg.b64), (c) => c.charCodeAt(0));
        return postBlob(msg.sceneId, new Blob([bin], { type: msg.mime || 'image/jpeg' }), msg.name || 'flow.jpg');
      }
      case 'cdpAttach':
        return cdpAttach(sender.tab.id);
      case 'cdpDetach':
        return cdpDetach(sender.tab.id);
      case 'cdpClick':
        await cdpAttach(sender.tab.id);
        return cdpClick(sender.tab.id, msg.x, msg.y);
      case 'cdpMove':
        await cdpAttach(sender.tab.id);
        return cdp(sender.tab.id, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x: msg.x, y: msg.y });
      case 'cdpKey':
        await cdpAttach(sender.tab.id);
        return cdpKey(sender.tab.id, msg.key, msg.modifiers || 0);
      case 'cdpType':
        await cdpAttach(sender.tab.id);
        return cdp(sender.tab.id, 'Input.insertText', { text: msg.text });
      case 'cdpSetFileInputs': { // put files into the page's <input type=file> elements, shadow DOM included
        await cdpAttach(sender.tab.id);
        const tabId = sender.tab.id;
        const { root } = await cdp(tabId, 'DOM.getDocument', { depth: -1, pierce: true });
        const inputs = [];
        const walk = (n) => {
          if (!n) return;
          if (n.nodeName === 'INPUT') {
            const a = {};
            for (let i = 0; i + 1 < (n.attributes || []).length; i += 2) a[n.attributes[i]] = n.attributes[i + 1];
            if ((a.type || '').toLowerCase() === 'file') inputs.push({ id: n.backendNodeId, accept: (a.accept || '').toLowerCase(), multiple: 'multiple' in a });
          }
          (n.children || []).forEach(walk);
          (n.shadowRoots || []).forEach(walk);
          if (n.contentDocument) walk(n.contentDocument);
        };
        walk(root);
        // image inputs first; an input that only takes video is skipped
        const usable = inputs.filter((i) => !i.accept || /image|\*|png|jpe?g|webp/.test(i.accept))
          .sort((x, y) => (/image/.test(y.accept) ? 1 : 0) - (/image/.test(x.accept) ? 1 : 0));
        if (!usable.length) throw new Error('trang không có ô tải tệp ảnh (' + inputs.length + ' ô tệp)');
        const pick = msg.index != null ? usable[Math.min(msg.index, usable.length - 1)] : usable[0];
        await cdp(tabId, 'DOM.setFileInputFiles', { files: msg.paths, backendNodeId: pick.id });
        return { count: usable.length };
      }
      case 'cdpReadImage': { // read the picture inside the page itself (handles blob: links and the page's cookies)
        await cdpAttach(sender.tab.id);
        const expr = `(async (src) => {
          const toData = (blob) => new Promise((res, rej) => { const f = new FileReader(); f.onload = () => res(f.result); f.onerror = rej; f.readAsDataURL(blob); });
          const el = [...document.images].find((i) => (i.currentSrc || i.src) === src);
          try {
            const r = await fetch(src, { credentials: 'include' });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const blob = await r.blob();
            if (!blob.type.startsWith('image/')) throw new Error('không phải ảnh: ' + blob.type);
            return { data: await toData(blob), w: el ? el.naturalWidth : 0, h: el ? el.naturalHeight : 0 };
          } catch (e) {
            if (!el) throw e;
            const c = document.createElement('canvas');
            c.width = el.naturalWidth; c.height = el.naturalHeight;
            c.getContext('2d').drawImage(el, 0, 0);
            return { data: c.toDataURL('image/png'), w: c.width, h: c.height };
          }
        })(${JSON.stringify(msg.src)})`;
        const r = await cdp(sender.tab.id, 'Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
        if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description?.split('\n')[0] || 'lỗi đọc ảnh trong trang');
        const v = r.result.value;
        const m = /^data:([^;]+);base64,(.*)$/.exec(v.data || '');
        if (!m) throw new Error('dữ liệu ảnh không hợp lệ');
        return { b64: m[2], mime: m[1], w: v.w, h: v.h };
      }
      case 'cdpShot': { // last resort: screenshot of the image area (scaled up to the image's own resolution)
        await cdpAttach(sender.tab.id);
        const r = await cdp(sender.tab.id, 'Page.captureScreenshot', { format: 'png', clip: msg.clip, captureBeyondViewport: true });
        return r.data;
      }
      case 'cdpArmFiles': { // the next file dialog in this tab gets these files (absolute paths)
        await cdpAttach(sender.tab.id);
        armed.set(sender.tab.id, armFiles(sender.tab.id, msg.paths, msg.timeoutMs || 10000).wait);
        return true;
      }
      case 'cdpFilesDone': {
        const w = armed.get(sender.tab.id);
        armed.delete(sender.tab.id);
        if (!w) throw new Error('chưa chuẩn bị tệp');
        return w;
      }
      case 'patchFileInputs': // run in the page's own world so a click on "Upload" does not open the file dialog
        await chrome.scripting.executeScript({ target: { tabId: sender.tab.id }, world: 'MAIN', func: patchFileInputs });
        return true;
      default:
        throw new Error('unknown message ' + msg.type);
    }
  })().then((data) => sendResponse({ ok: true, data }), (err) => sendResponse({ ok: false, error: String(err?.message || err) }));
  return true; // async response
});

async function postBlob(sceneId, blob, name) {
  const base = await appBase();
  const fd = new FormData();
  fd.append('file', blob, name);
  const res = await fetch(`${base}/api/flow-ext/tasks/${sceneId}/result`, { method: 'POST', body: fd });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

// Injected into the page (MAIN world). While <html data-ms-capture="1"> is set, a programmatic click on a file
// input does not open the OS dialog; the input is marked (and attached to the document) so content.js can fill it.
function patchFileInputs() {
  if (window.__msFilePatch) return;
  window.__msFilePatch = true;
  const orig = HTMLInputElement.prototype.click;
  HTMLInputElement.prototype.click = function () {
    if (this.type === 'file' && document.documentElement.dataset.msCapture === '1') {
      this.setAttribute('data-ms-capture', '1');
      if (!this.isConnected) { this.style.display = 'none'; document.body.appendChild(this); }
      document.documentElement.dataset.msCapture = 'got';
      return;
    }
    return orig.apply(this, arguments);
  };
  const origPicker = HTMLInputElement.prototype.showPicker;
  if (origPicker) {
    HTMLInputElement.prototype.showPicker = function () {
      if (this.type === 'file' && document.documentElement.dataset.msCapture === '1') return this.click();
      return origPicker.apply(this, arguments);
    };
  }
}
