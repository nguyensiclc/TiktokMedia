// Runs on the Media Studio page (localhost). When the app queues scenes for Flow it posts a message on the page;
// this passes it to the extension, which opens Flow and starts at once. It also tells the app the extension is here.
(() => {
  if (window.__msFlowBridge) return;
  window.__msFlowBridge = true;
  document.documentElement.dataset.msFlowExt = chrome.runtime.getManifest().version;

  window.addEventListener('message', (e) => {
    if (e.source !== window || e.data?.source !== 'media-studio') return;
    if (e.data.type !== 'flow-ext-queued') return;
    // appUrl: the extension works for whichever local app sent the signal (several apps can share it).
    chrome.runtime.sendMessage({ type: 'wake', reason: 'app', appUrl: location.origin }, (res) => {
      void chrome.runtime.lastError;
      window.postMessage({ source: 'media-studio-ext', type: 'flow-ext-woken', ok: !!res?.ok, data: res?.data, error: res?.error }, '*');
    });
  });
})();
