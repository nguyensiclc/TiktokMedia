<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'
import { ask } from '../confirm'
import JobLog from '../components/JobLog.vue'
import VoicePicker from '../components/VoicePicker.vue'

const flow = ref(null)
const checkJob = ref(null)
let flowTimer = null

async function loadFlow() {
  try { flow.value = await api.flowStatus() } catch { flow.value = null }
}

async function flowCheck() {
  error.value = ''
  try {
    const j = await api.flowCheck()
    checkJob.value = j.id
  } catch (e) {
    error.value = e.message
  }
}

onBeforeUnmount(() => clearInterval(flowTimer))

// --- TikTok ---
const tt = ref(null)
const ttWaiting = ref(false)
let ttTimer = null

async function loadTiktok() {
  try { tt.value = await api.tiktokStatus() } catch { tt.value = null }
}

async function tiktokConnect() {
  error.value = ''
  try {
    await save()
    const { url } = await api.tiktokLogin()
    window.open(url, '_blank') // Electron opens this in the system browser
    ttWaiting.value = true
    clearInterval(ttTimer)
    let n = 0
    ttTimer = setInterval(async () => {
      await loadTiktok()
      if (tt.value?.connected || ++n > 90) {
        clearInterval(ttTimer)
        ttWaiting.value = false
      }
    }, 2000)
  } catch (e) {
    error.value = e.message
  }
}

async function tiktokDisconnect() {
  if (!(await ask({ title: 'Ngắt kết nối TikTok?', message: 'Media Studio sẽ không đăng được lên tài khoản này cho tới khi bạn kết nối lại.', confirmLabel: 'Ngắt kết nối', danger: true, icon: 'alert' }))) return
  await api.tiktokLogout()
  loadTiktok()
}

async function copy(text) {
  try { await navigator.clipboard.writeText(text); msg.value = 'Đã copy' } catch { /* ignore */ }
}

onBeforeUnmount(() => clearInterval(ttTimer))

const vieneuJob = ref(null)
async function startVieneu() {
  error.value = ''
  try {
    await save()
    vieneuJob.value = (await api.post('/api/voice/vieneu/start')).id
  } catch (e) {
    error.value = e.message
  }
}

const s = ref(null)
const profiles = ref([])
const profileError = ref('')
const checks = ref([])
const msg = ref('')
const error = ref('')
const checking = ref(false)

async function load() {
  s.value = await api.settings()
  loadProfiles()
  runCheck()
}

async function loadProfiles() {
  try {
    profiles.value = await api.voiceProfiles()
    profileError.value = ''
  } catch (e) {
    profiles.value = []
    profileError.value = e.message
  }
}

async function runCheck() {
  checking.value = true
  try {
    checks.value = await api.check()
  } finally {
    checking.value = false
  }
}

// --- Use on a phone (same Wi-Fi) ---
const lan = ref({ enabled: false, pin: '', urls: [] })
async function loadLan() {
  try { lan.value = await api.get('/api/lan/info') } catch { /* opened from a phone: not available */ }
}
async function toggleLan() {
  await save()
  await loadLan()
}
async function newPin() {
  try { lan.value = await api.post('/api/lan/pin') ; s.value.lanPin = lan.value.pin } catch (e) { error.value = e.message }
}
onMounted(loadLan)

async function save() {
  error.value = ''
  try {
    s.value = await api.saveSettings(s.value)
    msg.value = 'Đã lưu cài đặt'
    setTimeout(() => (msg.value = ''), 2000)
    loadProfiles()
  } catch (e) {
    error.value = e.message
  }
}

async function call(fn) {
  error.value = ''
  try {
    await save()
    const r = await fn()
    msg.value = r.hint
  } catch (e) {
    error.value = e.message
  }
}

const flowApi = ref(null)
async function checkFlowApi() {
  flowApi.value = null
  try {
    await save() // test the address typed above
    flowApi.value = await api.flowApiHealth()
  } catch (e) {
    flowApi.value = { ok: false, error: e.message }
  }
}

onMounted(() => {
  load()
  loadFlow()
  loadTiktok()
  flowTimer = setInterval(loadFlow, 3000)
})
</script>

<template>
  <h1>Cài đặt</h1>
  <div v-if="error" class="error-bar">{{ error }}</div>
  <div v-if="msg" class="panel ok" style="margin-bottom:12px">{{ msg }}</div>

  <div v-if="s" class="wrap">
    <div class="panel">
      <div class="row"><h2 class="grow">Kiểm tra môi trường</h2><button :disabled="checking" @click="runCheck">↻ Kiểm tra</button></div>
      <div v-for="c in checks" :key="c.name" class="row check">
        <span :class="c.ok ? 'ok' : 'err'">{{ c.ok ? '●' : '●' }}</span>
        <strong style="width:220px">{{ c.name }}</strong>
        <span class="muted">{{ c.detail }}</span>
      </div>
    </div>

    <div class="panel">
      <h2><Icon name="zap" />Tạo ảnh nhanh bằng Gemini API</h2>
      <p class="muted">Gọi thẳng model ảnh của Google (Nano Banana), không cần mở trình duyệt hay chạy Claude: mỗi ảnh vài giây, nhiều cảnh cùng lúc,
        có gửi kèm ảnh nhân vật và ảnh mẫu phong cách. Lấy key tại <a href="https://aistudio.google.com/apikey" target="_blank">aistudio.google.com → Get API key</a>
        (cần bật thanh toán; tính phí theo ảnh, xem bảng giá của Google).</p>
      <div class="grid2">
        <div class="field"><label>Gemini API key</label><input v-model="s.geminiApiKey" type="password" autocomplete="off" placeholder="AIza…" /></div>
        <div class="field"><label>Model ảnh</label>
          <input v-model="s.geminiImageModel" list="gemini-models" />
          <datalist id="gemini-models">
            <option value="gemini-3.1-flash-image">Nano Banana 2 (ổn định)</option>
            <option value="gemini-nano-banana-2.1">Nano Banana 2.1 (mới)</option>
            <option value="gemini-3-pro-image">Nano Banana Pro (đẹp hơn, chậm và đắt hơn)</option>
            <option value="gemini-3.1-flash-lite-image">Nano Banana 2 Lite (rẻ, kém giữ nhân vật)</option>
          </datalist>
        </div>
        <div class="field"><label>Độ phân giải</label>
          <select v-model="s.geminiImageSize"><option>1K</option><option>2K</option><option>4K</option></select>
        </div>
        <div class="field"><label>Số cảnh tạo cùng lúc</label><input v-model.number="s.geminiParallel" type="number" min="1" max="6" /></div>
      </div>
    </div>

    <div class="panel">
      <h3><Icon name="flow" />Tạo ảnh bằng Google Flow</h3>
      <p class="hint">Mỗi câu lệnh đã chứa đủ mô tả phong cách và nhân vật giống hệt nhau ở mọi cảnh, nên các cảnh vẫn khớp nhau.</p>
      <div class="field"><label for="flow-engine">Cách tạo</label>
        <select id="flow-engine" v-model="s.flowEngine">
          <option value="api">Service tạo ảnh của tôi (API)</option>
          <option value="extension">Extension Chrome “Media Studio → Flow”</option>
        </select>
      </div>
      <template v-if="s.flowEngine !== 'extension'">
        <div class="grid2">
          <div class="field"><label for="flow-api-url">Địa chỉ service</label><input id="flow-api-url" v-model="s.flowApiUrl" placeholder="http://localhost:8080" /></div>
          <div class="field"><label for="flow-api-batch">Số ảnh mỗi lần gọi</label><input id="flow-api-batch" v-model.number="s.flowApiBatch" type="number" min="1" max="10" /></div>
          <div class="field"><label for="flow-api-timeout">Thời gian chờ tối thiểu (giây)</label><input id="flow-api-timeout" v-model.number="s.flowApiTimeoutSec" type="number" min="120" max="900" /></div>
          <p class="hint" style="grid-column: 1 / -1; margin: 0">Service tạo lần lượt từng ảnh (~30 giây/ảnh) và chỉ trả kết quả khi ảnh đã hoàn chỉnh, nên app chờ tối đa max(số trên, số ảnh × 60) giây mỗi lần gọi, không cần kiểm tra lại.</p>
        </div>
        <label class="check"><input type="checkbox" v-model="s.flowApiDeleteWhenDone" /> Xoá project trên Flow khi đã lấy đủ hình về máy</label>
        <p class="hint" style="margin-top:0">Mỗi dự án dùng một project Flow riêng. Khi mọi cảnh đã có hình trong máy, project đó bị xoá trên Flow để Flow không dồn hàng trăm ảnh (trang nhẹ và ổn định hơn). Không hoàn tác được, nhưng hình đã nằm trong máy.</p>
        <div class="row">
          <button @click="checkFlowApi"><Icon name="flask" /> Kiểm tra service</button>
          <span v-if="flowApi" :class="flowApi.ok ? 'ok' : 'err'">{{ flowApi.ok ? 'Service đang chạy' : flowApi.error }}</span>
        </div>
      </template>
      <template v-else>
        <label class="check"><input type="checkbox" v-model="s.flowExtUseRefs" /> Gắn thêm ảnh tham chiếu (giống hơn nhưng chậm hơn nhiều)</label>
        <div class="field"><label>Nghỉ giữa các cảnh (giây)</label><input v-model.number="s.flowExtPauseSeconds" type="number" min="0" max="120" /></div>
      </template>
    </div>

    <div class="panel">
      <h2>Kết nối MCP qua Claude CLI</h2>
      <p class="muted">Mỗi tác vụ chạy <code>claude -p --mcp-config …</code> với các server dưới đây. Cần đăng nhập 1 lần cho mỗi dịch vụ.</p>
      <div class="grid2">
        <div class="box">
          <label class="row chk"><input v-model="s.enableFlow" type="checkbox" /> <strong>Google Flow</strong> (Playwright MCP)</label>
          <div class="field"><label>Flow URL</label><input v-model="s.flowUrl" /></div>
          <div class="field"><label>Gói Playwright MCP</label><input v-model="s.playwrightMcpPackage" /></div>
          <div class="field"><label>Trình duyệt (--browser)</label>
            <select v-model="s.playwrightBrowser"><option>chrome</option><option>msedge</option><option>chromium</option></select>
          </div>
          <div class="field"><label>Thư mục profile trình duyệt (trống = workspace/browser-profile)</label><input v-model="s.browserProfileDir" /></div>
          <div class="field"><label>Đường dẫn chrome.exe (trống = tự tìm)</label><input v-model="s.chromePath" /></div>
          <div v-if="flow" class="status">
            <div>
              <span :class="flow.account ? 'ok' : 'err'">●</span>
              {{ flow.account ? `Đã đăng nhập Google: ${flow.account}` : 'Chưa đăng nhập Google trong profile này' }}
            </div>
            <div v-if="flow.chromeOpen">
              <span class="warn">●</span> Chrome đang mở với profile này. <strong>Hãy đóng cửa sổ Chrome đó</strong>, vì Claude không dùng được profile khi nó đang mở.
            </div>
            <div v-else-if="flow.account"><span class="ok">●</span> Chrome đã đóng, sẵn sàng cho Claude điều khiển</div>
          </div>
          <div class="row">
            <button @click="call(api.flowLogin)"><Icon name="key" /> Mở Chrome để đăng nhập</button>
            <button :disabled="!flow?.account || flow?.chromeOpen" @click="flowCheck"><Icon name="flask" /> Kiểm tra bằng Claude</button>
          </div>
          <div v-if="checkJob" style="margin-top:10px"><JobLog :job-id="checkJob" /></div>
        </div>
        <div class="box">
          <label class="row chk"><input v-model="s.enableCanva" type="checkbox" /> <strong>Canva</strong> (remote MCP, OAuth)</label>
          <div class="field"><label>Canva MCP URL</label><input v-model="s.canvaMcpUrl" /></div>
          <button @click="call(api.mcpLogin)"><Icon name="key" /> Mở Claude CLI để đăng nhập (/mcp)</button>

          <template v-if="s.voiceProvider === 'voicebox'">
            <label class="row chk" style="margin-top:20px"><input v-model="s.enableVoiceboxMcp" type="checkbox" /> <strong>Voicebox MCP</strong> (cho lệnh tự do)</label>
            <p class="muted small">Tạo giọng cho cảnh dùng REST API trực tiếp, không cần MCP.</p>
          </template>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="row">
        <h2 class="grow">TikTok</h2>
        <span v-if="tt?.connected" class="badge ok-badge">● Đã kết nối</span>
        <span v-else class="badge">Chưa kết nối</span>
      </div>

      <div v-if="tt?.connected" class="row acct">
        <img v-if="tt.avatarUrl" :src="tt.avatarUrl" class="avatar" />
        <div class="grow">
          <strong>{{ tt.displayName || 'Tài khoản TikTok' }}</strong>
          <div class="muted small">Quyền: {{ (tt.scopes || []).join(', ') }}</div>
          <div v-if="tt.userError" class="err small">{{ tt.userError }}</div>
        </div>
        <button class="small danger" @click="tiktokDisconnect">Ngắt kết nối</button>
      </div>

      <details :open="!tt?.configured" class="setup">
        <summary>Thiết lập app TikTok (làm 1 lần)</summary>
        <ol class="small steps">
          <li>Vào <a href="https://developers.tiktok.com/apps/" target="_blank">developers.tiktok.com</a> → tạo app (loại Desktop).</li>
          <li>Thêm sản phẩm <strong>Login Kit</strong> và <strong>Content Posting API</strong>; bật scope <code>video.upload</code>
            (và <code>video.publish</code> nếu muốn đăng thẳng).</li>
          <li>Trong Login Kit → Redirect URI (Desktop), thêm:
            <div class="row"><code class="grow">{{ tt?.redirectUriToRegister || 'http://127.0.0.1:*/api/tiktok/callback/' }}</code>
              <button class="small" @click="copy(tt?.redirectUriToRegister)">Copy</button></div>
          </li>
          <li>Copy <strong>Client key</strong> và <strong>Client secret</strong> vào bên dưới rồi bấm Kết nối.</li>
        </ol>
        <p class="muted small">
          Khi app chưa được TikTok duyệt (audit), video đăng thẳng sẽ chỉ ở chế độ <strong>Chỉ mình tôi</strong> và tài khoản
          cần đặt riêng tư. "Gửi vào hộp nháp" thì bạn tự chọn quyền riêng tư khi đăng trong app TikTok.
        </p>
      </details>

      <div class="grid2">
        <div class="field"><label>Client key</label><input v-model="s.tiktokClientKey" autocomplete="off" /></div>
        <div class="field"><label>Client secret (chỉ lưu trên máy này)</label>
          <input v-model="s.tiktokClientSecret" type="password" autocomplete="off" /></div>
      </div>
      <div class="row">
        <label class="row chk" style="margin:0"><input v-model="s.tiktokScopeUpload" type="checkbox" /> Gửi vào hộp nháp (<code>video.upload</code>)</label>
        <label class="row chk" style="margin:0"><input v-model="s.tiktokScopePublish" type="checkbox" /> Đăng thẳng (<code>video.publish</code>)</label>
        <span class="grow" />
        <select v-model="s.tiktokPkceEncoding" style="width:auto" title="Chỉ đổi nếu TikTok báo lỗi code_challenge">
          <option value="hex">PKCE: hex (theo tài liệu TikTok)</option>
          <option value="base64url">PKCE: base64url</option>
        </select>
        <button class="primary" :disabled="!s.tiktokClientKey || !s.tiktokClientSecret || ttWaiting" @click="tiktokConnect">
          {{ ttWaiting ? 'Đang chờ bạn đồng ý trên TikTok…' : tt?.connected ? 'Kết nối lại' : 'Kết nối TikTok' }}
        </button>
      </div>
      <div v-if="ttWaiting" class="muted small" style="margin-top:6px">
        Trình duyệt đã mở trang TikTok. Đăng nhập và bấm Đồng ý, trang này sẽ tự cập nhật.
      </div>
    </div>

    <div class="panel">
      <div class="row">
        <h2 class="grow">Giọng đọc</h2>
        <select v-model="s.voiceProvider" style="width:240px" @change="save">
          <option value="vieneu">VieNeu-TTS (tiếng Việt)</option>
          <option value="voicebox">Voicebox</option>
        </select>
      </div>

      <div class="field speed">
        <label>
          Tốc độ đọc: <strong>×{{ Number(s.voiceSpeed || 1).toFixed(2) }}</strong>
          <span class="muted small">
            ({{ (s.voiceSpeed || 1) < 0.97 ? 'chậm hơn, hợp đọc thơ' : (s.voiceSpeed || 1) > 1.03 ? 'nhanh hơn' : 'bình thường' }})
          </span>
        </label>
        <div class="row">
          <span class="muted small">0.5</span>
          <input v-model.number="s.voiceSpeed" class="grow" type="range" min="0.5" max="1.5" step="0.05" @change="save" />
          <span class="muted small">1.5</span>
          <button class="small" :disabled="(s.voiceSpeed || 1) === 1" @click="s.voiceSpeed = 1; save()">Đặt lại</button>
        </div>
        <p class="muted small" style="margin:4px 0 0">
          Mặc định cho các dự án chưa cài riêng; mỗi dự án đổi được giọng và tốc độ ở khung "Giọng đọc của dự án". Giữ nguyên cao độ giọng.
        </p>
      </div>

      <template v-if="s.voiceProvider !== 'voicebox'">
        <div class="grid2">
          <div class="field">
            <label>python.exe của venv đã cài <code>vieneu</code></label>
            <input v-model="s.vieneuPython" placeholder="D:\...\.venv-vieneu\Scripts\python.exe" />
          </div>
          <div class="field"><label>VieNeu server URL</label><input v-model="s.vieneuUrl" /></div>
          <div class="field">
            <label>Thiết bị</label>
            <select v-model="s.vieneuDevice">
              <option value="auto">Tự động (GPU nếu có)</option><option value="cpu">CPU</option><option value="cuda">CUDA</option>
            </select>
          </div>
        </div>
        <div class="row">
          <label class="row chk" style="margin:0"><input v-model="s.vieneuAutoStart" type="checkbox" /> Tự khởi động server khi cần</label>
          <span class="grow" />
          <button @click="startVieneu"><Icon name="play" /> Khởi động VieNeu</button>
        </div>
        <div v-if="vieneuJob" style="margin-top:10px"><JobLog :job-id="vieneuJob" @done="loadProfiles" /></div>

        <div class="field" style="margin-top:14px">
          <div class="row">
            <label class="grow" style="margin:0">Giọng đọc mặc định cho video: nghe thử và chọn</label>
            <button class="small" @click="loadProfiles">↻ Tải lại danh sách</button>
          </div>
          <div v-if="profileError" class="muted small" style="margin:6px 0">
            {{ profileError }}. Bấm "Khởi động VieNeu" ở trên rồi đợi khoảng 20 giây.
          </div>
          <VoicePicker v-else :model-value="s.vieneuVoice" :voices="profiles" :speed="s.voiceSpeed || 1"
                       :default-option="{ id: '', name: 'Mặc định (Hải Đăng)', gender: 'male' }"
                       @update:model-value="(v) => { s.vieneuVoice = v; save() }" />
        </div>
      </template>

      <div v-else class="grid2">
        <div class="field"><label>Voicebox URL</label><input v-model="s.voiceboxUrl" /></div>
        <div class="field" style="grid-column: 1 / -1">
          <div class="row">
            <label class="grow" style="margin:0">Voice profile mặc định: nghe thử và chọn</label>
            <button class="small" @click="loadProfiles">↻ Tải lại danh sách</button>
          </div>
          <div v-if="profileError" class="muted small" style="margin:6px 0">{{ profileError }}</div>
          <VoicePicker v-else :model-value="s.voiceProfileId" :voices="profiles" :speed="s.voiceSpeed || 1"
                       @update:model-value="(v) => { s.voiceProfileId = v; save() }" />
        </div>
        <div class="field">
          <label>Ngôn ngữ (mã Voicebox: en, ja, ko, zh, fr, de, es…)</label>
          <input v-model="s.voiceLanguage" />
        </div>
        <div class="field">
          <label>Kích thước model (lần đầu Voicebox sẽ tự tải model)</label>
          <select v-model="s.voiceModelSize">
            <option value="">Mặc định của Voicebox</option>
            <option>1.7B</option><option>0.6B</option><option>1B</option><option>3B</option>
          </select>
        </div>
      </div>
    </div>

    <div class="panel">
      <h2>Công cụ & thư mục</h2>
      <div class="grid2">
        <div class="field"><label>Workspace</label><input v-model="s.workspaceDir" /></div>
        <div class="field"><label>Claude CLI</label><input v-model="s.claudePath" /></div>
        <div class="field"><label>Model Claude chung (trống = mặc định)</label><input v-model="s.claudeModel" placeholder="vd: sonnet, opus" /></div>
        <div class="field" style="grid-column: 1 / -1">
          <label>Công cụ AI cho: viết kịch bản · viết mô tả TikTok</label>
          <select v-model="s.aiWriting" style="width:320px" @change="save">
            <option value="claude">Claude CLI (có thể đổi model bên dưới)</option>
            <option value="agy">Antigravity CLI (agy), ít tốn token Claude</option>
          </select>
          <div class="muted small" style="margin-top:4px">
            agy không nhìn được ảnh, nên phân tích hình và viết kịch bản cho dự án có ảnh nhân vật luôn dùng Claude. Tạo hình Canva/Flow cũng luôn dùng Claude vì cần MCP.
          </div>
        </div>
        <template v-if="s.aiWriting === 'agy'">
          <div class="field"><label>agy (đường dẫn)</label><input v-model="s.agyPath" placeholder="agy" /></div>
          <div class="field"><label>Model agy (lệnh <code>agy models</code>)</label>
            <input v-model="s.agyModel" list="agy-models" placeholder="gemini-3.8-flash-high" />
            <datalist id="agy-models">
              <option value="gemini-3.8-flash-high" /><option value="gemini-3.8-flash-medium" /><option value="gemini-3.8-flash-low" />
              <option value="claude-sonnet-4-6" /><option value="claude-opus-4-6-thinking" /><option value="gpt-oss-120b-medium" />
            </datalist>
          </div>
        </template>
        <div class="field"><label>Model Claude cho phân tích hình{{ s.aiWriting === 'agy' ? '' : ' & viết (trống = model chung)' }}</label><input v-model="s.claudeModelWriting" placeholder="opus" /></div>
        <div class="field"><label>FFmpeg</label><input v-model="s.ffmpegPath" /></div>
        <div class="field"><label>FFprobe</label><input v-model="s.ffprobePath" /></div>
      </div>
    </div>

    <div class="panel">
      <h3><Icon name="phone" />Dùng trên điện thoại (cùng Wi-Fi)</h3>
      <label class="row chk"><input v-model="s.lanAccess" type="checkbox" @change="toggleLan" /> Cho phép điện thoại trong mạng Wi-Fi nhà mở app này</label>
      <template v-if="s.lanAccess && lan.enabled">
        <div class="status">
          <div>Mở trên điện thoại (Safari):</div>
          <div v-for="u in lan.urls" :key="u"><code>{{ u }}</code></div>
          <div v-if="!lan.urls.length" class="warn">Chưa thấy địa chỉ mạng nội bộ. Kiểm tra máy tính đã nối Wi-Fi.</div>
          <div style="margin-top:6px">Mã PIN: <strong style="font-size:18px;letter-spacing:.2em">{{ lan.pin }}</strong>
            <button class="small" style="margin-left:8px" @click="newPin">Đổi mã</button></div>
        </div>
        <p class="muted small" style="margin:0">
          Máy tính phải đang mở app. Lần đầu Windows có thể hỏi tường lửa: chọn cho phép trên <strong>mạng riêng</strong>.
          Điện thoại nhập PIN một lần rồi được nhớ 30 ngày. Kết nối chưa mã hoá (http) nên chỉ dùng ở Wi-Fi nhà bạn tin tưởng.
          Từ điện thoại không đổi được Cài đặt, không đăng nhập/đăng xuất dịch vụ và không chạy lệnh tự do cho Claude.
        </p>
      </template>
      <p v-else class="muted small" style="margin:0">Đang tắt: chỉ máy tính này truy cập được.</p>
    </div>

    <div class="row" style="justify-content:flex-end"><button class="primary" @click="save">Lưu cài đặt</button></div>
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; gap: 12px; max-width: 1000px; }
.check { padding: 4px 0; border-bottom: 1px solid var(--border); }
.box { background: var(--panel-2); border-radius: 10px; padding: 12px; }
.chk { gap: 6px; color: var(--text); font-size: 14px; margin-bottom: 10px; }
.chk input { width: auto; }
.small { font-size: 12px; }
.status { background: var(--bg); border-radius: 8px; padding: 8px 10px; margin-bottom: 10px; line-height: 1.7; font-size: 13px; }
.warn { color: var(--warn); }
.acct { margin: 6px 0 10px; }
.avatar { width: 36px; height: 36px; border-radius: 50%; }
.ok-badge { color: var(--ok); border-color: var(--ok); }
.setup { margin: 8px 0 12px; }
.setup summary { cursor: pointer; color: var(--accent-2); }
.steps { line-height: 1.8; padding-left: 18px; }
code { background: var(--bg); padding: 1px 5px; border-radius: 4px; }
</style>
