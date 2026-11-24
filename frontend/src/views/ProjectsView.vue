<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, fileUrl } from '../api'
import { ask } from '../confirm'
import AutopilotPanel from '../components/AutopilotPanel.vue'
import { confirmNoDuplicates, SYNC_STATE } from '../dupes'

/** The posts list: every project as one row with its posting time, progress and state. */
const router = useRouter()
const projects = ref([])
const status = ref({ paused: false, current: null })
const error = ref('')
const filter = ref('all')
const showAdd = ref(false)
const showManual = ref(false)
const showLog = ref(false)
const name = ref('')
const aspect = ref('9:16')
let timer = null

const STATE = {
  QUEUED: { label: 'Chờ chuẩn bị', cls: 'wait' },
  PREPARING: { label: 'Đang chuẩn bị', cls: 'run' },
  READY: { label: 'Sẵn sàng', cls: 'ready' },
  POSTING: { label: 'Đang đăng', cls: 'run' },
  POSTED: { label: 'Đã đăng', cls: 'ok' },
  DRAFTED: { label: 'Đã lưu nháp', cls: 'ok' },
  FAILED: { label: 'Lỗi', cls: 'bad' },
}
const NICHE = { history: 'Lịch sử', story: 'Truyện', facts: 'Bạn có biết' }
const FILTERS = [
  { id: 'all', label: 'Tất cả' },
  { id: 'queue', label: 'Đang chờ' },
  { id: 'posted', label: 'Đã đăng' },
  { id: 'failed', label: 'Lỗi' },
  { id: 'none', label: 'Ngoài danh sách' },
]

/** Posts of the other computers sharing the list through a synced folder (names and states only). */
const sync = ref({ enabled: false, machines: [] })
const SYNC_CLS = { QUEUED: 'wait', PREPARING: 'run', READY: 'ready', POSTING: 'run', POSTED: 'ok', DRAFTED: 'ok', FAILED: 'bad', NONE: 'none' }
const otherRows = computed(() => sync.value.machines
  .flatMap((m) => m.posts.map((p) => ({ ...p, machine: m.machine, key: m.machineId + '/' + p.id })))
  .sort((a, b) => new Date(b.postedAt || b.scheduledAt || b.createdAt || 0) - new Date(a.postedAt || a.scheduledAt || a.createdAt || 0)))
const filters = computed(() => (sync.value.enabled ? [...FILTERS, { id: 'others', label: 'Máy khác' }] : FILTERS))

async function load() {
  try {
    const [ps, st, sy] = await Promise.all([api.projects(), api.get('/api/queue/status'), api.get('/api/sync/posts').catch(() => null)])
    projects.value = ps
    status.value = st
    if (sy) sync.value = sy
    error.value = ''
  } catch (e) {
    error.value = 'Không kết nối được backend: ' + e.message
  }
}
function loop() {
  clearTimeout(timer)
  timer = setTimeout(async () => { await load(); loop() }, 5000)
}

/** Progress of the 5 steps, computed from what the project already has. */
function steps(p) {
  const withPrompt = p.scenes.filter((s) => (s.imagePrompt || '').trim()).length
  const withMedia = p.scenes.filter((s) => s.mediaFile).length
  const script = p.scenes.some((s) => (s.narration || '').trim())
  const posted = p.queueState === 'POSTED' || (p.tiktokPosts || []).some((t) => ['POSTED_VIA_STUDIO', 'DRAFT_VIA_STUDIO'].includes(t.status))
  return [
    { k: 'Kịch bản', done: script, text: script ? p.scenes.length + ' cảnh' : '' },
    { k: 'Hình', done: script && withPrompt > 0 && withMedia >= withPrompt, part: withMedia > 0 && withMedia < withPrompt, text: script ? `${withMedia}/${withPrompt}` : '' },
    { k: 'Ảnh lướt', done: (p.slideFiles || []).length > 0, text: (p.slideFiles || []).length ? p.slideFiles.length + ' ảnh' : '' },
    { k: 'Caption', done: !!(p.tiktokCaption || '').trim() },
    { k: p.queueDraft ? 'Nháp' : 'Đăng', done: posted },
  ]
}
function stateOf(p) {
  if (p.queueDraft && p.queueState === 'POSTED') return STATE.DRAFTED
  if (p.queueDraft && p.queueState === 'POSTING') return { label: 'Đang lưu nháp', cls: 'run' }
  if (p.queueState) return STATE[p.queueState] || { label: p.queueState, cls: '' }
  return steps(p)[4].done ? STATE.POSTED : { label: 'Ngoài danh sách', cls: 'none' }
}
function thumb(p) {
  if ((p.slideFiles || []).length) return fileUrl(p.id, 'output', p.slideFiles[0], p.updatedAt)
  const s = p.scenes.find((x) => x.mediaFile && !/\.mp4$/i.test(x.mediaFile))
  return s ? fileUrl(p.id, 'assets', s.mediaFile, p.updatedAt) : null
}
const working = (p) => p.queueState === 'PREPARING' || p.queueState === 'POSTING'

const rows = computed(() => {
  const order = { POSTING: 0, PREPARING: 1, READY: 2, QUEUED: 3, FAILED: 4 }
  const list = projects.value.filter((p) => {
    if (filter.value === 'queue') return ['QUEUED', 'PREPARING', 'READY', 'POSTING'].includes(p.queueState)
    if (filter.value === 'posted') return stateOf(p) === STATE.POSTED
    if (filter.value === 'failed') return p.queueState === 'FAILED'
    if (filter.value === 'none') return !p.queueState
    return true
  })
  const t = (p) => (p.scheduledAt ? new Date(p.scheduledAt).getTime() : Infinity)
  return [...list].sort((a, b) => {
    const oa = order[a.queueState] ?? 9, ob = order[b.queueState] ?? 9
    if (oa !== ob) return oa - ob
    if (oa <= 4) return t(a) - t(b)
    return new Date(b.postedAt || b.updatedAt) - new Date(a.postedAt || a.updatedAt)
  })
})
const counts = computed(() => ({
  queue: projects.value.filter((p) => ['QUEUED', 'PREPARING', 'READY', 'POSTING'].includes(p.queueState)).length,
  failed: projects.value.filter((p) => p.queueState === 'FAILED').length,
}))
const nextPost = computed(() => projects.value
  .filter((p) => ['QUEUED', 'PREPARING', 'READY'].includes(p.queueState) && p.scheduledAt)
  .sort((a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt))[0])
const currentProject = computed(() => projects.value.find((p) => p.id === status.value.current?.projectId))

function toLocalInput(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
const fmt = (iso) => new Date(iso).toLocaleString('vi-VN', { weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
function rel(iso) {
  const m = Math.round((new Date(iso) - Date.now()) / 60000)
  if (Math.abs(m) < 1) return 'bây giờ'
  const a = Math.abs(m)
  const s = a < 60 ? a + ' phút' : a < 48 * 60 ? Math.round(a / 60) + ' giờ' : Math.round(a / 1440) + ' ngày'
  return m > 0 ? 'còn ' + s : s + ' trước'
}

async function act(p, body) {
  try {
    const fresh = await api.post(`/api/queue/projects/${p.id}`, body)
    Object.assign(p, fresh)
    setTimeout(load, 1500)
  } catch (e) {
    error.value = e.message
  }
}
function setTime(p, v) {
  act(p, { action: 'queue', scheduledAt: v ? new Date(v).toISOString() : '' })
}
async function postNow(p) {
  const ok = await ask({
    title: `Đăng "${p.name}" ngay?`,
    message: p.queueState === 'READY' ? 'Bài đã sẵn sàng, sẽ được đăng trong vòng một phút.'
      : 'Bài sẽ được chuẩn bị (kịch bản, hình, ảnh lướt, caption) rồi đăng ngay khi xong.',
    confirmLabel: 'Đăng ngay', icon: 'send',
  })
  if (ok) act(p, { action: 'now' })
}
async function removeFromQueue(p) {
  const ok = await ask({
    title: `Bỏ "${p.name}" khỏi danh sách đăng?`,
    message: working(p) ? 'Bước đang chạy sẽ bị dừng. Dự án và những gì đã tạo vẫn được giữ.' : 'Dự án vẫn được giữ, chỉ không tự đăng nữa.',
    confirmLabel: 'Bỏ khỏi danh sách', danger: true, icon: 'x',
  })
  if (ok) act(p, { action: 'remove' })
}
async function remove(p) {
  if (!(await ask({ title: `Xoá dự án "${p.name}"?`, message: 'Toàn bộ cảnh, hình, video và ảnh đã xuất của dự án sẽ bị xoá. Không hoàn tác được.', confirmLabel: 'Xoá dự án', danger: true }))) return
  if (working(p)) await api.post(`/api/queue/projects/${p.id}`, { action: 'remove' })
  await api.deleteProject(p.id)
  load()
}
async function togglePause() {
  try {
    status.value = { ...status.value, ...(await api.post('/api/queue/pause', { paused: !status.value.paused })) }
  } catch (e) {
    error.value = e.message
  }
}
async function create() {
  try {
    if (!(await confirmNoDuplicates([name.value]))) return
    const p = await api.createProject(name.value, aspect.value)
    router.push(`/project/${p.id}`)
  } catch (e) {
    error.value = e.message
  }
}
function added(list) {
  showAdd.value = false
  filter.value = 'queue'
  load()
}
const open = (p) => router.push(`/project/${p.id}`)

onMounted(async () => { await load(); loop(); window.addEventListener('ms:refresh', load) })
onBeforeUnmount(() => { clearTimeout(timer); window.removeEventListener('ms:refresh', load) })
</script>

<template>
  <div class="head">
    <h1>Danh sách bài đăng</h1>
    <span class="grow" />
    <button @click="showManual = !showManual"><Icon name="file" /> Dự án thủ công</button>
    <button class="primary" @click="showAdd = !showAdd"><Icon name="plus" /> Thêm bài</button>
  </div>
  <div v-if="error" class="error-bar">{{ error }}</div>

  <AutopilotPanel v-if="showAdd" class="block" @added="added" @error="(m) => (error = m)" />

  <div v-if="showManual" class="panel block manual">
    <div class="grow">
      <label>Tên dự án</label>
      <input v-model="name" placeholder="Tự làm từng bước trong dự án" @keyup.enter="create" />
    </div>
    <div>
      <label>Khung hình</label>
      <select v-model="aspect">
        <option value="9:16">9:16 (TikTok / Shorts)</option>
        <option value="16:9">16:9 (YouTube)</option>
        <option value="1:1">1:1</option>
      </select>
    </div>
    <button class="primary" @click="create">Tạo và mở</button>
  </div>

  <!-- worker status -->
  <div class="panel block worker" :class="{ paused: status.paused }">
    <span class="dot" :class="status.current ? 'run' : status.paused ? 'off' : 'idle'" aria-hidden="true" />
    <div class="grow wtext">
      <template v-if="status.paused"><b>Đã tạm dừng.</b> Không chuẩn bị hay đăng bài nào cho tới khi chạy lại.</template>
      <template v-else-if="status.current">
        <b>{{ status.current.title }}</b>
        <span v-if="currentProject?.queueStep" class="muted"> · {{ currentProject.queueStep }}</span>
      </template>
      <template v-else-if="counts.queue">
        <b>Đang chờ.</b>
        <span v-if="nextPost" class="muted"> Bài kế tiếp: {{ nextPost.name }}, {{ fmt(nextPost.scheduledAt) }} ({{ rel(nextPost.scheduledAt) }})</span>
      </template>
      <template v-else><b>Không có bài nào trong danh sách.</b> <span class="muted">Bấm "Thêm bài" để xếp lịch.</span></template>
    </div>
    <button v-if="status.current" class="small" @click="showLog = !showLog">{{ showLog ? 'Ẩn nhật ký' : 'Nhật ký' }}</button>
    <button class="small" @click="togglePause"><Icon :name="status.paused ? 'play' : 'pause'" /> {{ status.paused ? 'Chạy lại' : 'Tạm dừng' }}</button>
  </div>
  <pre v-if="showLog && status.current" class="log block">{{ status.current.log.join('\n') }}</pre>

  <div class="filters" role="tablist">
    <button v-for="f in filters" :key="f.id" role="tab" :aria-selected="filter === f.id" :class="{ on: filter === f.id }" @click="filter = f.id">
      {{ f.label }}<span v-if="f.id === 'queue' && counts.queue" class="cnt">{{ counts.queue }}</span><span v-if="f.id === 'failed' && counts.failed" class="cnt bad">{{ counts.failed }}</span>
    </button>
  </div>

  <div v-if="filter === 'others'" class="table panel">
    <div v-if="sync.error" class="sync-err small">{{ sync.error }}</div>
    <div class="tr th others">
      <div>Bài</div><div>Máy</div><div>Giờ đăng</div><div>Trạng thái</div>
    </div>
    <div v-for="p in otherRows" :key="p.key" class="tr others ro">
      <div class="pt">
        <strong>{{ p.name }}</strong>
        <span class="muted small">{{ NICHE[p.niche] || p.niche }}<template v-if="p.deleted"> · dự án đã xoá</template></span>
      </div>
      <div class="small">{{ p.machine }}</div>
      <div class="small">
        <template v-if="p.postedAt">{{ fmt(p.postedAt) }}</template>
        <template v-else-if="p.scheduledAt && p.state !== 'NONE'">{{ fmt(p.scheduledAt) }}</template>
        <span v-else class="muted">—</span>
      </div>
      <div><span class="badge" :class="SYNC_CLS[p.state]">{{ SYNC_STATE[p.state] || p.state }}</span></div>
    </div>
    <div v-if="!otherRows.length" class="empty muted">
      Chưa thấy bài của máy nào khác. Trên máy kia, vào Cài đặt → "Đồng bộ danh sách giữa các máy" và chọn cùng thư mục này:
      <code>{{ sync.dir }}</code>
    </div>
  </div>

  <div v-else class="table panel">
    <div class="tr th">
      <div>Bài</div><div>Tiến độ</div><div>Giờ đăng</div><div>Trạng thái</div><div />
    </div>
    <div v-for="p in rows" :key="p.id" class="tr" :class="{ active: working(p) }" tabindex="0"
         @click="open(p)" @keyup.enter="open(p)">
      <div class="post">
        <div class="thumb"><img v-if="thumb(p)" :src="thumb(p)" alt="" loading="lazy" /><Icon v-else name="image" /></div>
        <div class="pt">
          <strong>{{ p.name }}</strong>
          <span class="muted small">{{ NICHE[p.niche] || p.niche }} · {{ p.aspect }} · {{ p.scenes.length }} cảnh</span>
        </div>
      </div>
      <div class="steps">
        <span v-for="s in steps(p)" :key="s.k" class="step" :class="{ done: s.done, part: s.part }" :title="s.k + (s.text ? ': ' + s.text : '')">
          <Icon v-if="s.done" name="check" :size="11" />{{ s.k }}<small v-if="s.text && !s.done"> {{ s.text }}</small>
        </span>
      </div>
      <div class="when" @click.stop>
        <template v-if="p.queueState && p.queueState !== 'POSTED'">
          <input type="datetime-local" :value="toLocalInput(p.scheduledAt)" :disabled="p.queueState === 'POSTING'"
                 aria-label="Giờ đăng" @change="(e) => setTime(p, e.target.value)" />
          <span class="muted small">{{ p.queueDraft ? 'lưu nháp khi xong' : p.scheduledAt ? rel(p.scheduledAt) : 'chưa hẹn giờ' }}</span>
        </template>
        <span v-else-if="p.postedAt" class="small">{{ fmt(p.postedAt) }}</span>
        <span v-else class="muted small">—</span>
      </div>
      <div class="st">
        <span class="badge" :class="stateOf(p).cls">{{ stateOf(p).label }}</span>
        <span v-if="working(p) && p.queueStep" class="muted small">{{ p.queueStep }}</span>
        <span v-if="p.queueState === 'FAILED' && p.queueError" class="err small" :title="p.queueError">{{ p.queueError }}</span>
      </div>
      <div class="acts" @click.stop>
        <button v-if="p.queueState === 'FAILED'" class="small" @click="act(p, { action: 'retry' })">Thử lại</button>
        <button v-if="p.queueState && !['POSTED', 'POSTING'].includes(p.queueState)" class="small" title="Đăng ngay khi sẵn sàng" @click="postNow(p)"><Icon name="send" :size="13" /> Đăng ngay</button>
        <button v-if="!p.queueState" class="small" @click="act(p, { action: 'queue' })"><Icon name="plus" :size="13" /> Vào danh sách</button>
        <button v-if="p.queueState && p.queueState !== 'POSTED'" class="small icon" title="Bỏ khỏi danh sách" aria-label="Bỏ khỏi danh sách" @click="removeFromQueue(p)"><Icon name="x" :size="14" /></button>
        <button class="small icon danger" title="Xoá dự án" aria-label="Xoá dự án" @click="remove(p)"><Icon name="trash" :size="14" /></button>
      </div>
    </div>
    <div v-if="!rows.length" class="empty muted">Không có bài nào ở mục này.</div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.head h1 { margin: 0; }
.block { margin-bottom: 14px; }
.manual { display: flex; gap: 12px; align-items: flex-end; }

.worker { display: flex; align-items: center; gap: 10px; padding: 10px 14px; }
.worker.paused { background: var(--warn-soft); }
.wtext { font-size: 13px; min-width: 0; }
.dot { width: 10px; height: 10px; border-radius: 99px; flex: 0 0 10px; background: var(--muted); }
.dot.run { background: var(--accent); box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 25%, transparent); animation: pulse 1.4s ease-in-out infinite; }
.dot.idle { background: var(--ok); }
.dot.off { background: var(--warn-line); }
@keyframes pulse { 50% { box-shadow: 0 0 0 7px color-mix(in srgb, var(--accent) 5%, transparent); } }
@media (prefers-reduced-motion: reduce) { .dot.run { animation: none; } }
.log { margin-top: -6px; max-height: 240px; overflow: auto; padding: 10px; border-radius: 8px; background: var(--fill);
       font-size: 12px; line-height: 1.5; white-space: pre-wrap; }

.filters { display: flex; gap: 2px; padding: 2px; border-radius: 9px; background: var(--fill); width: max-content; max-width: 100%;
           overflow-x: auto; margin-bottom: 10px; }
.filters button { border-color: transparent; background: transparent; box-shadow: none; border-radius: 7px; font-size: 13px; gap: 6px; }
.filters button.on { background: var(--panel); box-shadow: 0 1px 3px rgba(0, 0, 0, .14), 0 0 0 .5px rgba(0, 0, 0, .06); font-weight: 600; }
.cnt { font-size: 11px; padding: 0 6px; border-radius: 99px; background: var(--accent); color: var(--on-accent); }
.cnt.bad { background: var(--danger, #D92D20); color: #fff; }

.table { padding: 0; overflow: hidden; }
.tr { display: grid; grid-template-columns: minmax(220px, 2.2fr) minmax(250px, 2.4fr) minmax(170px, 1.2fr) minmax(140px, 1.2fr) auto;
      gap: 12px; align-items: center; padding: 10px 14px; border-top: 1px solid var(--border); cursor: pointer; }
.tr:first-child { border-top: 0; }
.tr:not(.th):hover { background: var(--fill); }
.tr:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.tr.active { background: var(--accent-soft); }
.th { cursor: default; font-size: 11px; font-weight: 600; color: var(--muted); padding-top: 8px; padding-bottom: 8px; background: var(--panel-2); }
.post { display: flex; align-items: center; gap: 10px; min-width: 0; }
.thumb { flex: 0 0 42px; height: 56px; border-radius: 6px; overflow: hidden; background: var(--fill-strong); display: grid; place-items: center; color: var(--muted); }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.pt { display: flex; flex-direction: column; min-width: 0; }
.pt strong { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.steps { display: flex; flex-wrap: wrap; gap: 4px; }
.step { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; padding: 2px 7px; border-radius: 99px;
        background: var(--fill); color: var(--muted); white-space: nowrap; }
.step.done { background: color-mix(in srgb, var(--ok) 16%, transparent); color: var(--ok); font-weight: 600; }
.step.part { background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent); }
.when { display: flex; flex-direction: column; gap: 2px; cursor: default; }
.when input { font-size: 12px; padding: 3px 6px; min-height: 28px; }
.st { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; min-width: 0; }
.badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 99px; background: var(--fill-strong); }
.badge.wait { background: var(--fill-strong); color: var(--text); }
.badge.run { background: var(--accent); color: var(--on-accent); }
.badge.ready { background: color-mix(in srgb, var(--accent) 16%, transparent); color: var(--accent); }
.badge.ok { background: color-mix(in srgb, var(--ok) 18%, transparent); color: var(--ok); }
.badge.bad { background: #D92D20; color: #fff; }
.badge.none { background: transparent; color: var(--muted); border: 1px solid var(--border); }
.err { color: #D92D20; max-width: 100%; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.acts { display: flex; gap: 4px; justify-content: flex-end; cursor: default; }
.acts .icon { width: 28px; padding: 0; justify-content: center; }
.empty { padding: 24px; text-align: center; }
.tr.others { grid-template-columns: minmax(220px, 3fr) minmax(120px, 1fr) minmax(150px, 1.2fr) minmax(120px, 1fr); }
.tr.ro { cursor: default; }
.sync-err { padding: 10px 14px; color: #D92D20; border-bottom: 1px solid var(--border); }

@media (max-width: 1100px) {
  .tr { grid-template-columns: 1fr auto; grid-template-areas: "post acts" "steps steps" "when st"; }
  .th { display: none; }
  .post { grid-area: post; } .steps { grid-area: steps; } .when { grid-area: when; } .st { grid-area: st; align-items: flex-end; } .acts { grid-area: acts; }
}
@media (max-width: 1100px) {
  .tr.others { grid-template-columns: 1fr auto; grid-template-areas: none; }
  .tr.others > :nth-child(2), .tr.others > :nth-child(3) { font-size: 12px; color: var(--muted); }
}
@media (max-width: 820px) { .manual { flex-wrap: wrap; } .manual > * { flex: 1 1 100%; } .head { flex-wrap: wrap; } }
</style>
