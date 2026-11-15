const $ = (id) => document.getElementById(id);
const FLOW_HOME = 'https://labs.google/fx/tools/flow';
const isFlow = (u) => /^https:\/\/(flow\.google\.com|labs\.google)\//.test(u || '');

function send(msg) {
  return new Promise((resolve, reject) => chrome.runtime.sendMessage(msg, (res) => {
    if (chrome.runtime.lastError) return reject(new Error(chrome.runtime.lastError.message));
    res?.ok ? resolve(res.data) : reject(new Error(res?.error || 'lỗi'));
  }));
}
const api = (path, method = 'GET', body) => send({ type: 'api', path, method, body });

let session = null;

async function refresh() {
  try {
    await api('/api/flow-ext/ping');
    $('conn').innerHTML = '<span class="ok">●</span> Đã kết nối Media Studio';
  } catch (e) {
    $('conn').innerHTML = '<span class="err">●</span> Không kết nối được app. Mở Media Studio (web/app) trên máy này rồi thử lại.';
    $('start').disabled = true;
    return;
  }
  session = await api('/api/flow-ext/session');
  const q = $('queue');
  q.hidden = false;
  if (!session.active) {
    q.innerHTML = 'Không có cảnh nào đang chờ.<div class="muted">Trong Media Studio bấm “🌀 Flow qua extension”.</div>';
    $('start').disabled = true;
    return;
  }
  const pending = session.tasks.filter((t) => t.state === 'pending' || t.state === 'working');
  const done = session.tasks.filter((t) => t.state === 'done').length;
  q.innerHTML = `<b>${escapeHtml(session.projectName)}</b> · ${session.aspect}<br>`
    + `Còn ${pending.length} cảnh · đã xong ${done}/${session.tasks.length}`
    + `<ul>${pending.slice(0, 30).map((t) => `<li>Cảnh ${t.n}${t.state === 'working' ? ' (đang làm)' : ''} · ${t.refs.length} ảnh tham chiếu</li>`).join('')}</ul>`
    + (session.flowProjectUrl ? '<div class="muted">Dùng project Flow đã lưu của dự án.</div>' : '<div class="muted">Chưa có project Flow: extension sẽ tự tạo project mới.</div>');
  $('start').disabled = false;
}

function escapeHtml(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function flowTab() {
  const [active] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (active && isFlow(active.url)) return active;
  const all = await chrome.tabs.query({ url: ['https://flow.google.com/*', 'https://labs.google/*'] });
  return all.find((t) => t.url.includes('/project/')) || all[0] || null;
}

$('start').onclick = async () => {
  $('msg').textContent = '';
  try {
    const r = await send({ type: 'wake', reason: 'popup' });
    $('msg').textContent = !r.started ? 'Không có cảnh nào đang chờ.'
      : session?.flowProjectUrl ? 'Đang mở project Flow của dự án, sẽ tự bắt đầu…'
        : 'Đang mở Flow và tạo project mới, sẽ tự bắt đầu…';
  } catch (e) {
    $('msg').textContent = 'Lỗi: ' + e.message;
  }
};

$('stop').onclick = async () => {
  const tab = await flowTab();
  if (tab) chrome.tabs.sendMessage(tab.id, { type: 'stop' }, () => void chrome.runtime.lastError);
  await api('/api/flow-ext/stop', 'POST', { reason: 'Bấm Dừng trong extension' }).catch(() => {});
  $('msg').textContent = 'Đã dừng.';
  refresh();
};

chrome.storage.local.get('appUrl').then(({ appUrl }) => { $('app').value = appUrl || ''; });
$('app').onchange = async () => { await chrome.storage.local.set({ appUrl: $('app').value.trim() }); refresh(); };

refresh();
