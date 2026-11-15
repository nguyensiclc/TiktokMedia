// Runs inside the Google Flow page. Takes the queue from Media Studio and, for each scene: attaches the reference
// images, types the prompt, presses Create, waits for the new picture, downloads it and hands it to the app.
// It never tries to get around Google's checks: on "unusual activity" / CAPTCHA it stops at once.
(() => {
  if (window.__msFlowRunner) return;
  window.__msFlowRunner = true;

  // Labels in Vietnamese and English (Flow follows the Google account language).
  const L = {
    textbox: ['Bạn muốn tạo gì?', 'What do you want to create?'],
    addComponent: ['Thêm thành phần', 'Add component', 'Add ingredient', 'Add media', 'Add to prompt box'],
    addToPrompt: ['Thêm vào câu lệnh', 'Add to prompt'],
    upload: ['Tải nội dung', 'Tải lên', 'Upload'],
    create: ['Bắt đầu tạo', 'Create', 'Generate'],
    clear: ['Xoá câu lệnh', 'Xóa câu lệnh', 'Clear prompt'],
    download: ['Tải xuống', 'Download'],
    original: ['Kích thước gốc', 'Original size', '1K'],
    blocked: ['hoạt động bất thường', 'unusual activity', 'captcha', 'xác minh bạn không phải', 'verify you are', "i'm not a robot"],
    failed: ['không thể tạo', 'không tạo được', 'something went wrong', 'failed to generate', 'couldn\'t generate', 'vi phạm', 'policy'],
  };

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
  const labelOf = (el) => norm(el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent);
  const visible = (el) => !!el && el.isConnected && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';

  function send(msg) {
    return new Promise((resolve, reject) => {
      chrome.runtime.sendMessage(msg, (res) => {
        if (chrome.runtime.lastError) return reject(new Error(chrome.runtime.lastError.message));
        if (!res?.ok) return reject(new Error(res?.error || 'lỗi extension'));
        resolve(res.data);
      });
    });
  }
  const api = (path, method = 'GET', body) => send({ type: 'api', path, method, body });

  // ---------- small DOM helpers ----------
  function findButton(labels, root = document) {
    const want = labels.map(norm);
    const all = [...root.querySelectorAll('button, [role=button], [role=menuitem], a')].filter(visible);
    return all.find((b) => want.some((w) => labelOf(b) === w))
      || all.find((b) => want.some((w) => labelOf(b).startsWith(w)))
      || all.find((b) => want.some((w) => labelOf(b).includes(w)));
  }
  async function waitFor(fn, ms, step = 300) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      checkStop();
      const v = fn();
      if (v) return v;
      await sleep(step);
    }
    return null;
  }
  // Real (trusted) mouse and keyboard input through the background worker (chrome.debugger), like Playwright.
  // Coordinates are CSS pixels of the viewport; this assumes the page zoom is 100%.
  function center(el) {
    el.scrollIntoView({ block: 'center', inline: 'center' });
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
  }
  async function click(el) {
    const c = center(el);
    await sleep(120);
    try {
      await send({ type: 'cdpClick', x: c.x, y: c.y });
    } catch (e) {
      console.warn('[Media Studio → Flow] trusted click failed, using el.click():', e.message);
      el.click();
    }
    await sleep(150);
  }
  async function hover(el) {
    const c = center(el);
    await send({ type: 'cdpMove', x: c.x, y: c.y }).catch(() => {});
    await sleep(300);
  }
  const escape = () => send({ type: 'cdpKey', key: 'Escape' }).catch(() => {});

  function promptBox() {
    const want = L.textbox.map(norm);
    const boxes = [...document.querySelectorAll('[role=textbox], [contenteditable=true], textarea')].filter(visible);
    return boxes.find((b) => want.includes(norm(b.getAttribute('aria-label') || b.getAttribute('placeholder')))) || boxes[0];
  }
  function promptArea() {
    return document.querySelector('flow-base-prompt-box') || promptBox()?.closest('form, section, div') || document;
  }

  class Blocked extends Error {}
  function checkBlocked() {
    const t = norm(document.body.innerText).slice(0, 200000);
    if (L.blocked.some((w) => t.includes(w))) throw new Blocked('UNUSUAL_ACTIVITY: Google Flow báo hoạt động bất thường / cần xác minh');
  }

  // ---------- overlay with status + stop button ----------
  let stopRequested = false;
  let running = false;
  function checkStop() { if (stopRequested) throw new Error('Đã dừng'); }
  const panel = document.createElement('div');
  panel.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;background:#16202B;color:#fff;font:13px/1.45 system-ui,sans-serif;'
    + 'padding:12px 14px;border-radius:12px;box-shadow:0 6px 24px rgba(0,0,0,.35);max-width:340px;display:none';
  const ptext = document.createElement('div');
  ptext.style.cssText = 'white-space:pre-line;max-height:240px;overflow:auto;user-select:text';
  const pbtn = document.createElement('button');
  pbtn.textContent = 'Dừng';
  pbtn.style.cssText = 'margin-top:8px;min-height:32px;padding:0 14px;border-radius:8px;border:0;background:#B42318;color:#fff;cursor:pointer;font:inherit';
  pbtn.onclick = () => { stopRequested = true; status('Đang dừng sau bước hiện tại…'); };
  panel.append(Object.assign(document.createElement('b'), { textContent: 'Media Studio → Flow' }), ptext, pbtn);
  document.documentElement.appendChild(panel);
  function status(t) { panel.style.display = 'block'; ptext.textContent = t; console.log('[Media Studio → Flow]', t); }

  // ---------- reference images ----------
  function findOption(name) {
    const base = norm(name), stem = base.replace(/\.(png|jpe?g|webp)$/, '');
    const opts = [...document.querySelectorAll('[role=option], [role=listitem], [role=gridcell]')].filter(visible);
    return opts.find((o) => labelOf(o).startsWith(base)) || opts.find((o) => labelOf(o).includes(stem));
  }

  async function openPicker() {
    const add = findButton(L.addComponent, promptArea()) || findButton(L.addComponent);
    if (!add) throw new Error('không thấy nút "Thêm thành phần" trong ô nhập của Flow');
    await click(add);
    await sleep(900);
  }

  // Upload one reference file into Flow's library. Tries, in order:
  //  1) the page's own file inputs (found through the debugger, shadow DOM included) — no dialog at all;
  //  2) a real click on "Upload" whose file dialog the debugger fills (also when the click opens a small menu first);
  //  3) the old in-page way (DataTransfer on a captured input).
  // Returns a short reason when everything failed (the caller then continues without this reference).
  async function uploadRef(ref) {
    const errs = [];
    if (ref.path) {
      try {
        const r = await send({ type: 'cdpSetFileInputs', paths: [ref.path] });
        status(`Đang tải ảnh tham chiếu ${ref.name} lên Flow… (${r.count} ô tệp)`);
        return null;
      } catch (e) { errs.push('ô tệp: ' + e.message); }

      const up = findButton(L.upload);
      if (up) {
        try {
          await send({ type: 'cdpArmFiles', paths: [ref.path], timeoutMs: 8000 });
          await click(up);
          // "Upload" may open a small menu (computer / Drive…): pick the computer one.
          await sleep(700);
          const fromPc = findButton(['Tải lên từ máy tính', 'Từ máy tính', 'Máy tính', 'Thiết bị', 'From computer', 'Upload from computer', 'Computer', 'Device', 'Tải hình ảnh lên', 'Upload image']);
          if (fromPc && fromPc !== up) await click(fromPc);
          await send({ type: 'cdpFilesDone' });
          status(`Đang tải ảnh tham chiếu ${ref.name} lên Flow…`);
          return null;
        } catch (e) { errs.push('nút Tải lên: ' + e.message); }
      } else errs.push('không thấy nút "Tải nội dung"');
    }
    try {
      const { b64, type } = await send({ type: 'fetchRef', url: ref.url, name: ref.name });
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const file = new File([bytes], ref.name, { type: type || 'image/png' });
      await send({ type: 'patchFileInputs' });
      document.querySelectorAll('input[data-ms-capture]').forEach((i) => i.removeAttribute('data-ms-capture'));
      document.documentElement.dataset.msCapture = '1';
      const up2 = findButton(L.upload);
      if (up2) up2.click();
      let input = await waitFor(() => document.querySelector('input[type=file][data-ms-capture]'), 3000);
      if (!input) input = [...document.querySelectorAll('input[type=file]')].pop();
      delete document.documentElement.dataset.msCapture;
      if (!input) throw new Error('không có ô tệp');
      const dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return null;
    } catch (e) { errs.push('cách cũ: ' + e.message); }
    return errs.join(' | ');
  }

  /** Attaches one reference to the prompt. Returns null, or a warning when it had to be skipped. */
  async function attachRef(ref) {
    await openPicker();
    let opt = findOption(ref.name);
    if (!opt) {
      const err = await uploadRef(ref);
      if (err) {
        await escape();
        return `bỏ qua ${ref.role} (không tải lên được: ${err})`;
      }
      opt = await waitFor(() => findOption(ref.name), 60000, 1000);
      if (!opt) {
        // Some versions attach the uploaded file straight to the prompt: then there is nothing to pick.
        await escape();
        return null;
      }
    }
    await click(opt);
    await sleep(600);
    const confirm = findButton(L.addToPrompt);
    if (confirm) { await click(confirm); await sleep(600); }
    if (findOption(ref.name)) await escape(); // picker still open
    await sleep(300);
    return null;
  }

  // ---------- prompt + generate ----------
  async function clearPrompt() {
    const c = findButton(L.clear, promptArea());
    if (c && !c.disabled) { await click(c); await sleep(500); }
    const box = promptBox();
    if (box && norm(box.innerText || box.value)) {
      await click(box);
      await send({ type: 'cdpKey', key: 'a', modifiers: 2 }).catch(() => {});
      await send({ type: 'cdpKey', key: 'Backspace' }).catch(() => {});
    }
  }

  async function typePrompt(text) {
    const box = promptBox();
    if (!box) throw new Error('không thấy ô nhập câu lệnh của Flow');
    await click(box);
    box.focus();
    try {
      await send({ type: 'cdpKey', key: 'a', modifiers: 2 }); // Ctrl+A
      await send({ type: 'cdpKey', key: 'Backspace' });
      await send({ type: 'cdpType', text });
    } catch (e) {
      console.warn('[Media Studio → Flow] trusted typing failed:', e.message);
      document.execCommand('selectAll', false);
      document.execCommand('insertText', false, text);
    }
    await sleep(600);
    if (norm(box.innerText || box.value).length < Math.min(40, text.length / 2)) throw new Error('không nhập được câu lệnh vào Flow');
  }

  function createButton() {
    const b = findButton(L.create, promptArea());
    if (b) return b;
    return [...promptArea().querySelectorAll('button')].find((x) => visible(x) && /arrow_forward/.test(x.textContent));
  }

  // Result tiles: each generated picture opens /project/<id>/edit/<mediaId>.
  function tiles() {
    const out = new Map();
    for (const a of document.querySelectorAll('a[href*="/edit/"]')) {
      const id = a.getAttribute('href').split('/edit/')[1]?.split(/[?#/]/)[0];
      if (id && !out.has(id)) out.set(id, a.closest('[role=group]') || a);
    }
    for (const g of document.querySelectorAll('[role=group]')) {
      const img = g.querySelector('img');
      if (!img) continue;
      const key = 'img:' + (img.currentSrc || img.src).slice(0, 200);
      if (![...out.values()].includes(g) && !out.has(key)) out.set(key, g);
    }
    return out;
  }
  const readyImg = (tile) => [...tile.querySelectorAll('img')].find((i) => i.complete && i.naturalWidth >= 256);

  // Anything on the page that still says "generating": a percentage (e.g. "42%"), a progress bar or spinner,
  // or a "generating" label. Flow shows a blurred preview while it works, so an image alone means nothing.
  function generating(scope) {
    const root = scope || document;
    const progress = [...root.querySelectorAll('[role=progressbar], progress, mat-progress-spinner, mat-spinner, .spinner, [class*="progress"], [class*="loading"]')]
      .filter(visible);
    if (progress.length) return 'progress';
    const t = norm(root.innerText || '');
    if (/(^|\s)\d{1,3}\s?%(\s|$)/.test(t)) return 'percent';
    if (/đang tạo|đang xử lý|generating|in progress|processing|queued|đang chờ/.test(t)) return 'label';
    return null;
  }

  async function waitNewTile(before) {
    const end = Date.now() + 6 * 60 * 1000;
    let cand = null, stableSince = 0, sawProgress = false;
    while (Date.now() < end) {
      checkStop();
      checkBlocked();
      let found = null;
      for (const [k, el] of tiles()) {
        if (before.has(k)) continue;
        const t = norm(el.innerText);
        if (L.failed.some((w) => t.includes(w))) throw new Error('Flow không tạo được ảnh: ' + el.innerText.trim().slice(0, 160));
        found = { key: k, el };
        break;
      }
      const busyTile = found && generating(found.el);
      // page level: only a real progress bar or a percentage counts (class names elsewhere are too vague)
      const pageText = norm((document.querySelector('main') || document.body).innerText || '');
      const busyPage = [...document.querySelectorAll('[role=progressbar], progress')].some(visible)
        || /(^|\s)\d{1,3}\s?%(\s|$)/.test(pageText);
      if (busyTile || busyPage) sawProgress = true;
      const img = found && readyImg(found.el);
      const sig = img ? (img.currentSrc || img.src) + '|' + img.naturalWidth + 'x' + img.naturalHeight : null;
      if (found && img && !busyTile && !busyPage) {
        // finished only when the same full image has stayed put for 6 s with no progress anywhere
        if (!cand || cand.sig !== sig) { cand = { ...found, img, sig }; stableSince = Date.now(); }
        const left = Math.max(0, 6000 - (Date.now() - stableSince));
        status(`Ảnh đã hiện, chờ Flow hoàn tất… (${Math.ceil(left / 1000)}s)`);
        if (Date.now() - stableSince >= 6000 && img.naturalWidth >= 512) {
          await sleep(3000); // a few more seconds before downloading
          const again = readyImg(found.el);
          return { key: found.key, el: found.el, img: again || img, sawProgress };
        }
      } else {
        cand = null;
        const pct = /(\d{1,3})\s?%/.exec((found?.el.innerText || '') + ' ' + (promptArea().innerText || ''));
        status(pct ? `Flow đang tạo ảnh… ${pct[1]}%` : 'Flow đang tạo ảnh…');
      }
      await sleep(2000);
    }
    throw new Error('quá 6 phút chưa thấy ảnh hoàn tất');
  }

  // Gets the finished picture into Media Studio. Tries, in order: Flow's own Download (original size), the image
  // read inside the page itself (works for blob: and same-site links), the background worker (other Google hosts),
  // and finally a screenshot of the image. Every failure reason is kept so the task message says what went wrong.
  async function downloadTile(tile, sceneId) {
    const errs = [];
    const img = readyImg(tile.el) || tile.img;
    const src = new URL(img.currentSrc || img.src, location.href).href;
    const big = img.naturalWidth >= 1000;

    const viaMenu = async () => {
      await hover(tile.el);
      const buttons = [...tile.el.querySelectorAll('button')].filter(visible);
      const menuBtn = buttons.find((x) => /more_vert|more_horiz/.test(x.textContent) || /thêm|more|tuỳ chọn|tùy chọn|options/.test(labelOf(x)))
        || buttons[buttons.length - 1];
      if (!menuBtn) throw new Error('không thấy nút menu của ảnh');
      await click(menuBtn);
      const dl = await waitFor(() => findButton(L.download), 4000);
      if (!dl) throw new Error('không thấy mục Tải xuống');
      const waiting = send({ type: 'expectDownload', timeoutMs: 30000 });
      await click(dl);
      const size = await waitFor(() => findButton(L.original), 2500);
      if (size) await click(size);
      const file = await waiting;
      await escape();
      await api(`/api/flow-ext/tasks/${sceneId}/result-path`, 'POST', { path: file.path });
      await send({ type: 'removeDownload', id: file.id }); // the picture is now in the project; keep Downloads clean
      return file.name;
    };
    const viaPage = async () => {
      const r = await send({ type: 'cdpReadImage', src });
      if (!r?.b64) throw new Error('trang không trả về dữ liệu ảnh');
      await send({ type: 'uploadImageB64', sceneId, b64: r.b64, mime: r.mime, name: 'flow-image.jpg' });
      return `ảnh trên trang ${r.w || '?'}×${r.h || '?'}`;
    };
    const viaWorker = async () => {
      if (src.startsWith('blob:') || src.startsWith('data:')) throw new Error('liên kết blob/data');
      await send({ type: 'uploadImageUrl', sceneId, src, name: 'flow-image.jpg' });
      return 'ảnh tải qua extension';
    };
    const viaShot = async () => {
      const el = img;
      el.scrollIntoView({ block: 'center' });
      await sleep(300);
      const r = el.getBoundingClientRect();
      const scale = Math.max(1, Math.min(4, (el.naturalWidth || r.width) / Math.max(1, r.width)));
      const shot = await send({ type: 'cdpShot', clip: { x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height, scale } });
      await send({ type: 'uploadImageB64', sceneId, b64: shot, mime: 'image/png', name: 'flow-shot.png' });
      return 'ảnh chụp màn hình (độ nét thấp hơn)';
    };

    const order = big ? [['trang', viaPage], ['menu', viaMenu], ['extension', viaWorker], ['chụp', viaShot]]
      : [['menu', viaMenu], ['trang', viaPage], ['extension', viaWorker], ['chụp', viaShot]];
    for (const [name, fn] of order) {
      try {
        return await fn();
      } catch (e) {
        await escape();
        errs.push(`${name}: ${e.message || e}`);
        console.warn('[Media Studio → Flow] cách tải "' + name + '" thất bại:', e.message || e);
      }
    }
    throw new DownloadError('không tải được ảnh về (' + errs.join(' | ') + ')');
  }
  class DownloadError extends Error {}

  async function doTask(task) {
    checkBlocked();
    status(`Cảnh ${task.n}: chuẩn bị (${task.refs.length} ảnh tham chiếu)…`);
    await clearPrompt();
    const warnings = [];
    for (const ref of task.refs) {
      checkStop();
      status(`Cảnh ${task.n}: gắn ${ref.role} (${ref.name})`);
      const w = await attachRef(ref);
      if (w) { warnings.push(w); console.warn('[Media Studio → Flow]', w); }
    }
    task.warnings = warnings;
    status(`Cảnh ${task.n}: nhập câu lệnh…`);
    await typePrompt(task.prompt);
    const before = new Set(tiles().keys());
    const go = await waitFor(() => { const b = createButton(); return b && !b.disabled && b.getAttribute('aria-disabled') !== 'true' ? b : null; }, 8000);
    if (!go) throw new Error('nút Tạo của Flow đang bị khoá');
    await click(go);
    // If the prompt is still in the box and nothing new appeared, press once more.
    await sleep(2500);
    if (tiles().size === before.size && norm(promptBox()?.innerText || '').length > 40) {
      const again = createButton();
      if (again && !again.disabled) await click(again);
    }
    status(`Cảnh ${task.n}: Flow đang tạo ảnh…`);
    const tile = await waitNewTile(before);
    status(`Cảnh ${task.n}: đang tải ảnh về Media Studio…`);
    return downloadTile(tile, task.sceneId);
  }

  // ---------- project setup ----------
  const onProject = () => location.pathname.includes('/project/');

  /** On Flow's home page: press "New project", wait for the project page and name it after the Media Studio project. */
  async function newProject(name) {
    const btn = await waitFor(() => findButton(['Dự án mới', 'New project', 'Tạo dự án mới', 'Create new project']), 20000, 500);
    if (!btn) throw new Error('không thấy nút "Dự án mới" trên trang chủ Flow (đã đăng nhập Google chưa?)');
    await chrome.storage.local.set({ autostart: Date.now() }); // in case Flow reloads the page instead of routing
    await click(btn);
    if (!await waitFor(onProject, 30000, 500)) throw new Error('Flow chưa mở project mới sau 30 giây');
    await sleep(2500);
    await chrome.storage.local.remove('autostart');
    try {
      const title = [...document.querySelectorAll('[role=textbox], input')].filter(visible)
        .find((b) => /văn bản có thể chỉnh sửa|editable text/.test(labelOf(b)));
      if (title && name) {
        await click(title);
        await send({ type: 'cdpKey', key: 'a', modifiers: 2 });
        await send({ type: 'cdpType', text: 'Media Studio - ' + name });
        await send({ type: 'cdpKey', key: 'Enter' }).catch(() => {});
        await sleep(500);
      }
    } catch (e) { console.warn('[Media Studio → Flow] could not rename the project:', e.message); }
  }

  /** Sets the image aspect ratio and one result per prompt in the prompt box settings. Returns a warning or null. */
  async function applySettings(aspect) {
    const area = promptArea();
    const btn = findButton(['Cài đặt', 'Settings'], area !== document ? area : document);
    if (!btn) return 'không thấy nút Cài đặt — hãy tự chỉnh tỉ lệ ' + aspect + ' và x1';
    await click(btn);
    const group = (re) => [...document.querySelectorAll('[aria-label]')].filter(visible).find((g) => re.test(labelOf(g)) && g.querySelector('[role=radio]'));
    const ratio = await waitFor(() => group(/tỷ lệ khung hình.*hình ảnh|tỉ lệ khung hình.*hình ảnh|image.*aspect|aspect.*image/), 5000);
    const count = group(/số lượng kết quả.*hình ảnh|image.*(outputs|count)|(outputs|count).*image/);
    const warn = [];
    const pick = async (g, text) => {
      const r = g && [...g.querySelectorAll('[role=radio]')].find((x) => norm(x.getAttribute('aria-label') || x.textContent).includes(norm(text)));
      if (!r) return false;
      if (r.getAttribute('aria-checked') !== 'true') { await click(r); await sleep(400); }
      return true;
    };
    if (!await pick(ratio, aspect || '9:16')) warn.push('tỉ lệ ' + aspect);
    if (!await pick(count, 'x1')) warn.push('số ảnh x1');
    await escape();
    await sleep(400);
    return warn.length ? 'chưa tự chỉnh được ' + warn.join(', ') + ' — hãy chỉnh tay trong Cài đặt' : null;
  }

  // ---------- main loop ----------
  async function run() {
    if (running) return;
    running = true;
    stopRequested = false;
    try {
      let s = await api('/api/flow-ext/session');
      if (!s.active) { status('Không có cảnh nào đang chờ. Trong Media Studio, bấm "🌀 Flow qua extension".'); return; }
      let fresh = false;
      if (!onProject()) {
        if (s.flowProjectUrl) {
          await chrome.storage.local.set({ autostart: Date.now() });
          status('Đang mở project Flow của dự án…');
          location.href = s.flowProjectUrl;
          return;
        }
        status('Đang tạo project Flow mới cho "' + s.projectName + '"…');
        await send({ type: 'cdpAttach' });
        await newProject(s.projectName);
        fresh = true;
      }
      await api('/api/flow-ext/project', 'POST', { url: location.href });
      await send({ type: 'cdpAttach' });
      await waitFor(() => promptBox(), 20000, 500);
      status('Chỉnh tỉ lệ ' + s.aspect + ' và 1 ảnh mỗi lần tạo…');
      const setWarn = await applySettings(s.aspect);
      if (setWarn) { console.warn('[Media Studio → Flow]', setWarn); status('⚠ ' + setWarn); await sleep(fresh ? 1500 : 800); }
      let done = 0;
      let dlFails = 0;
      const problems = [];
      while (true) {
        checkStop();
        s = await api('/api/flow-ext/session');
        if (!s.active || s.stopped) break;
        const task = s.tasks.find((t) => t.state === 'pending');
        if (!task) break;
        await api(`/api/flow-ext/tasks/${task.sceneId}/start`, 'POST');
        try {
          const how = await doTask(task);
          done++;
          dlFails = 0;
          status(`✔ Cảnh ${task.n} xong (${how}).` + (task.warnings?.length ? ' ⚠ ' + task.warnings.join('; ') : ''));
          if (task.warnings?.length) problems.push(`Cảnh ${task.n}: ${task.warnings.join('; ')}`);
        } catch (e) {
          const msg = String(e.message || e);
          await api(`/api/flow-ext/tasks/${task.sceneId}/fail`, 'POST', { message: msg }).catch(() => {});
          if (e instanceof Blocked) { status('⛔ ' + msg + '. Đã dừng, không thử lại. Hãy đợi 30–60 phút.'); return; }
          if (stopRequested) break;
          status(`✖ Cảnh ${task.n}: ${msg}`);
          problems.push(`Cảnh ${task.n}: ${msg}`);
          if (e instanceof DownloadError && ++dlFails >= 2) {
            await api('/api/flow-ext/stop', 'POST', { reason: 'Hai cảnh liền không tải được ảnh về — dừng để không tạo thêm trong Flow' }).catch(() => {});
            status('⛔ Hai cảnh liền nhau Flow tạo xong nhưng không tải được về. Đã dừng. Lỗi: ' + msg);
            return;
          }
        }
        const left = (await api('/api/flow-ext/session')).tasks.filter((t) => t.state === 'pending').length;
        if (!left) break;
        const wait = (s.pauseSeconds || 15) * 1000 + Math.random() * 5000;
        status(`Nghỉ ${Math.round(wait / 1000)} giây trước cảnh tiếp theo (còn ${left} cảnh)…`);
        const end = Date.now() + wait;
        while (Date.now() < end) { checkStop(); await sleep(500); }
      }
      if (stopRequested) await api('/api/flow-ext/stop', 'POST', { reason: 'Bấm Dừng trên trang Flow' }).catch(() => {});
      status((stopRequested ? `Đã dừng. Xong ${done} cảnh.` : `Hoàn tất: ${done} cảnh đã gán vào Media Studio.`)
        + (problems.length ? '\n' + problems.slice(-4).join('\n') : ''));
    } catch (e) {
      if (stopRequested) {
        await api('/api/flow-ext/stop', 'POST', { reason: 'Bấm Dừng trên trang Flow' }).catch(() => {});
        status('Đã dừng.');
      } else status('Lỗi: ' + (e.message || e));
    } finally {
      running = false;
      send({ type: 'cdpDetach' }).catch(() => {});
    }
  }

  chrome.runtime.onMessage.addListener((msg, _s, reply) => {
    if (msg.type === 'start') { run(); reply({ ok: true }); }
    else if (msg.type === 'stop') { stopRequested = true; reply({ ok: true }); }
    else if (msg.type === 'status') reply({ ok: true, running, text: ptext.textContent });
  });

  // Start by itself when the extension opened this tab for a new queue (project page, or the home page where
  // run() creates a new project).
  chrome.storage.local.get('autostart').then(({ autostart }) => {
    if (autostart && Date.now() - autostart < 90000) {
      chrome.storage.local.remove('autostart');
      status('Media Studio đã mở Flow, sắp bắt đầu…');
      setTimeout(run, 4000);
    }
  });
})();
