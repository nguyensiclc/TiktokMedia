<script setup>
import { ref, computed, watch as vueWatch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, fileUrl } from '../api'
import { ask } from '../confirm'
import SceneCard from '../components/SceneCard.vue'
import JobLog from '../components/JobLog.vue'
import CharacterRefs from '../components/CharacterRefs.vue'
import TiktokPublish from '../components/TiktokPublish.vue'
import SourceImages from '../components/SourceImages.vue'
import DramaWriter from '../components/DramaWriter.vue'
import ProjectSound from '../components/ProjectSound.vue'
import SlidesPanel from '../components/SlidesPanel.vue'

const props = defineProps({ id: String })
const router = useRouter()

const project = ref(null)
const error = ref('')
const jobs = ref([])
const activeJob = ref(null)
const saving = ref(false)
const customPrompt = ref('')
const sb = ref({ topic: '', scenes: 6, language: 'Vietnamese', style: '', chibi: false, append: false, niche: 'story' })
const NICHES = [
  { id: 'story', label: 'Truyện · cổ tích', icon: 'file', hint: 'Có nhân vật xuyên suốt, cảnh mở đầu giới thiệu và cảnh cuối nêu bài học.',
    placeholder: 'VD: Sự tích cây vú sữa, giọng kể ấm áp' },
  { id: 'facts', label: 'Bạn có biết? · khoa học, vũ trụ', icon: 'sparkles', hint: 'Cảnh 1 là câu mở đầu gây tò mò, mỗi cảnh giữa là một sự thật có thật (càng về sau càng bất ngờ), cảnh cuối hỏi người xem để kéo bình luận. Không cần nhân vật.',
    placeholder: 'VD: 5 sự thật đáng sợ về hố đen, giọng kể hào hứng' },
  { id: 'history', label: 'Lịch sử · chiến tranh', icon: 'flag', hint: 'Luôn 7 phần theo thứ tự: mở đầu gây tò mò, bối cảnh và nguyên nhân, lực lượng hai bên, diễn biến (dài nhất), kết quả, ý nghĩa, kết. Chỉ dùng sự kiện, số liệu, lời nói có thật trong sử sách, không giả sử, không bịa. Tối đa 34 cảnh (cộng ảnh bìa là 35 ảnh, mức tối đa của TikTok).',
    placeholder: 'VD: Trận Bạch Đằng năm 938, Ngô Quyền đánh quân Nam Hán' },
  { id: 'drama', label: 'Drama · phim ngắn', icon: 'film', hint: 'Dán nguyên văn kịch bản một tập. Claude chia thành shot (mỗi shot một hình, tối đa một câu thoại), giữ đúng từng câu thoại. Không có người dẫn. Nhân vật được giữ nguyên qua các tập.',
    placeholder: 'CẢNH 1 – NỘI. PHÒNG HỌP LỚN TẦNG 18 – SÁNG\n(0:00) Cận mặt một tập hồ sơ bị ném mạnh xuống bàn…\nKHẢI: Ngân sách quý bốn bị gửi thẳng cho đối thủ…' },
]
const DRAMA_SHOTS = 40
const MAX_SHOTS = 80
const HISTORY_TOPICS = ['Hai Bà Trưng khởi nghĩa', 'Ngô Quyền và trận Bạch Đằng 938', 'Lý Thường Kiệt đánh Tống', 'Kháng chiến chống Nguyên Mông lần 3', 'Khởi nghĩa Lam Sơn', 'Quang Trung đại phá quân Thanh', 'Nội chiến Trịnh – Nguyễn', 'Tây Sơn và Nguyễn Ánh']
const FACT_TOPICS = ['5 sự thật khó tin về vũ trụ', 'Điều gì xảy ra nếu Mặt Trời biến mất?', 'Sự thật về hố đen', 'Những loài vật kỳ lạ dưới đáy đại dương', 'Cơ thể người có những bí mật gì?', 'Sao Kim: hành tinh địa ngục', 'Nếu Trái Đất ngừng quay', 'Những kỷ lục tự nhiên đáng kinh ngạc']
const niche = computed(() => NICHES.find((n) => n.id === sb.value.niche) || NICHES[0])
// a facts video = hook + 5 facts + closing question
// history = the fixed 7-part, 34-scene structure. The niche is saved on the project so "Phân tích" follows it too.
vueWatch(() => sb.value.niche, (n, old) => {
  if (!old) return
  if (n === 'facts' && sb.value.scenes === 6) sb.value.scenes = 7
  if (n === 'history') sb.value.scenes = 34
  else if (n === 'drama') sb.value.scenes = DRAMA_SHOTS
  else if (sb.value.scenes > 34 || (old === 'history' && sb.value.scenes === 34) || old === 'drama') sb.value.scenes = n === 'facts' ? 7 : 6
  if (project.value && project.value.niche !== n) { project.value.niche = n; changed() }
})

let saveTimer = null
const watchers = new Map()

const version = computed(() => project.value?.updatedAt || '')
const running = computed(() => jobs.value.filter((j) => j.status === 'RUNNING'))
const analyzing = computed(() => running.value.some((j) => j.type === 'analyze' || j.type === 'storyboard'))
// Scenes without a picture (and with an image prompt): the only ones "Flow tất cả" / "Canva tất cả" make.
const emptyCount = computed(() => (project.value?.scenes || []).filter((s) => !s.mediaFile && (s.imagePrompt || '').trim()).length)
const scenesAll = computed(() => project.value?.scenes || [])
const missingCount = computed(() => scenesAll.value.filter((s) => !s.mediaFile).length)
// On-screen text (drama messages, caption cards) is shown, never voiced.
const isScreenText = (s) => (s.speaker || '').trim().toUpperCase() === 'TEXT'
const castNames = computed(() => (project.value?.cast || []).map((c) => c.name))
const lastFlowFailed = computed(() => { const j = jobs.value.find((x) => /flow/i.test(x.title || '')); return !!j && j.status === 'FAILED' })

// The workflow as five steps (script, pictures, music, export; no voice); each shows where the project stands so the next move is obvious.
const steps = computed(() => {
  const p = project.value
  if (!p) return []
  const n = scenesAll.value.length
  return [
    { id: 'nguon', n: 1, label: 'Nguồn & nhân vật', note: (p.sources || []).length ? `${p.sources.length} ảnh nguồn` : 'Không bắt buộc', state: 'idle' },
    { id: 'kichban', n: 2, label: 'Kịch bản', note: n ? `${n} cảnh` : 'Chưa có cảnh', state: n ? 'ok' : 'todo' },
    { id: 'hinh', n: 3, label: 'Cảnh & hình ảnh', note: !n ? '—' : missingCount.value ? `Còn ${missingCount.value} cảnh trống` : 'Đủ hình', state: !n ? 'idle' : missingCount.value ? 'todo' : 'ok' },
    { id: 'amthanh', n: 4, label: 'Nhạc nền', note: p.musicFile ? 'Có nhạc nền' : 'Không bắt buộc', state: p.musicFile ? 'ok' : 'idle' },
    { id: 'xuat', n: 5, label: 'Xuất & đăng', note: p.lastRender ? 'Đã xuất' : 'Chưa xuất', state: p.lastRender ? 'ok' : 'idle' },
  ]
})

// One tab per step, remembered per project in this browser. Until one is picked: scenes if there are any, else sources.
const TAB_KEY = 'ms.tab.' + props.id
const picked = ref((() => { try { return localStorage.getItem(TAB_KEY) || '' } catch { return '' } })())
const tab = computed(() => (picked.value && picked.value !== 'dung' ? picked.value : (scenesAll.value.length ? 'hinh' : 'nguon')))
function goto(id) {
  picked.value = id
  try { localStorage.setItem(TAB_KEY, id) } catch { /* ignore */ }
}

// One suggested next action, in plain words.
const next = computed(() => {
  if (!project.value) return null
  if (!scenesAll.value.length) {
    return { tone: 'info', title: 'Bắt đầu: tạo kịch bản', text: 'Nhập chủ đề (hoặc phân tích từ ảnh) để Claude chia thành các cảnh.',
      actions: [{ label: 'Tới phần kịch bản', primary: true, go: () => goto('kichban') }] }
  }
  if (missingCount.value) {
    const n = missingCount.value
    return { tone: lastFlowFailed.value ? 'warn' : 'info',
      title: `Còn ${n} cảnh chưa có hình`,
      text: lastFlowFailed.value
        ? 'Lần tạo bằng Flow gần nhất bị lỗi (thường là Google chặn tạm thời). Chờ 30–60 phút rồi thử lại, hoặc tải hình lên thủ công ở danh sách cảnh.'
        : 'Tạo bằng Google Flow (qua service tạo ảnh của bạn, khoảng 15–40 giây mỗi ảnh), hoặc Gemini (nhanh, có phí), hoặc tải hình lên. Cảnh đã có hình được giữ nguyên.',
      actions: [
        { icon: 'flow', label: `Tạo ${emptyCount.value} cảnh bằng Flow`, primary: true, disabled: !emptyCount.value, go: () => run(() => api.flowExt(props.id)) },
        { icon: 'zap', label: 'Bằng Gemini', disabled: !emptyCount.value, go: () => run(() => api.geminiAll(props.id, true)) },
        { label: 'Tạo bằng Canva', disabled: !emptyCount.value, go: () => run(() => api.canvaAll(props.id, true)) },
        { label: 'Xem các cảnh', go: () => goto('hinh') },
      ] }
  }
  if (!project.value.lastRender) {
    return { tone: 'ok', title: 'Mọi thứ đã sẵn sàng', text: 'Xuất video hoặc danh sách ảnh. Những lần sau chỉ dựng lại các cảnh bạn vừa sửa nên nhanh hơn.',
      actions: [{ label: 'Xuất video', primary: true, go: () => run(() => api.render(props.id)) }, { label: 'Tới phần xuất & đăng', go: () => goto('xuat') }] }
  }
  return { tone: 'ok', title: 'Video đã xuất', text: 'Xem lại video ở cột bên phải, hoặc đăng lên TikTok / xuất danh sách ảnh.',
    actions: [{ label: 'Tới phần xuất & đăng', primary: true, go: () => goto('xuat') }] }
})

const busyScenes = computed(() => new Set(running.value.map((j) => j.sceneId).filter(Boolean)))
// Same rule as the backend: script mode shows each scene for words / 3 seconds (at least 2s).
const scriptSeconds = (t, fallback) => {
  const w = (t || '').trim().split(/\s+/).filter(Boolean).length
  return w ? Math.max(2, w / 3) : fallback
}
const totalDuration = computed(() =>
  (project.value?.scenes || []).reduce((t, s) => {
    const voice = !project.value.scriptMode && s.audioFile && s.audioDuration
    if (s.customDuration > 0.2) return t + (voice ? Math.max(s.customDuration, s.audioDuration + 0.3) : s.customDuration)
    if (project.value.scriptMode) return t + scriptSeconds(s.narration, s.duration || 4)
    return t + (s.audioFile ? (s.audioDuration || 0) + 0.3 : s.duration || 4)
  }, 0)
)

async function load() {
  try {
    project.value = await api.project(props.id)
    sb.value.topic = sb.value.topic || project.value.topic || ''
    sb.value.niche = project.value.niche || 'story'
    if (sb.value.niche === 'history') sb.value.scenes = 34
    if (sb.value.niche === 'drama') sb.value.scenes = DRAMA_SHOTS
  } catch (e) {
    error.value = e.message
  }
}

async function loadJobs() {
  jobs.value = await api.jobs(props.id)
  jobs.value.filter((j) => j.status === 'RUNNING').forEach(watch)
  if (!activeJob.value && jobs.value.length) activeJob.value = jobs.value[0].id
}

// Listen for each running job's completion so the project reloads with new media.
function watch(job) {
  if (watchers.has(job.id)) return
  const es = new EventSource(`/api/jobs/${job.id}/stream`)
  watchers.set(job.id, es)
  es.addEventListener('status', async (e) => {
    const st = JSON.parse(e.data)
    const j = jobs.value.find((x) => x.id === job.id)
    if (j) { j.status = st.status; j.error = st.error }
    es.close()
    watchers.delete(job.id)
    await flush()
    await load()
  })
  es.onerror = () => { es.close(); watchers.delete(job.id) }
}

async function run(promise) {
  error.value = ''
  try {
    await flush()
    const job = await promise()
    jobs.value.unshift(job)
    activeJob.value = job.id
    watch(job)
  } catch (e) {
    error.value = e.message
  }
}

function changed() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(save, 700)
}

async function save() {
  clearTimeout(saveTimer)
  saveTimer = null
  if (!project.value) return
  saving.value = true
  try {
    const fresh = await api.saveProject(project.value)
    // Keep local text (user may still be typing); take server-owned fields.
    project.value.updatedAt = fresh.updatedAt
    project.value.lastRender = fresh.lastRender
    project.value.canvaFolderId = fresh.canvaFolderId
    project.value.canvaDesignId = fresh.canvaDesignId
    project.value.tiktokPosts = fresh.tiktokPosts
    project.value.styleRefMediaId = fresh.styleRefMediaId
    project.value.styleRefSceneId = fresh.styleRefSceneId
    project.value.musicFile = fresh.musicFile
    project.value.slideFiles = fresh.slideFiles
    project.value.slidesZip = fresh.slidesZip
    project.value.slidesMusic = fresh.slidesMusic
    project.value.coverTitle = fresh.coverTitle
    project.value.coverTeaser = fresh.coverTeaser
    project.value.coverStyle = fresh.coverStyle
    for (const s of project.value.scenes) {
      const f = fresh.scenes.find((x) => x.id === s.id)
      if (f) Object.assign(s, { mediaFile: f.mediaFile, mediaSource: f.mediaSource, audioFile: f.audioFile, audioDuration: f.audioDuration, canvaMediaId: f.canvaMediaId, canvaDesignId: f.canvaDesignId })
    }
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

async function flush() {
  if (saveTimer) await save()
}

function addScene() {
  project.value.scenes.push({ id: Math.random().toString(16).slice(2, 10), narration: '', imagePrompt: '', duration: 4 })
  changed()
}

async function removeScene(i) {
  if (!(await ask({ title: `Xoá cảnh ${i + 1}?`, message: 'Lời script và hình của cảnh sẽ bị xoá.', confirmLabel: 'Xoá', danger: true }))) return
  project.value.scenes.splice(i, 1)
  changed()
}

function move(i, d) {
  const arr = project.value.scenes
  const [s] = arr.splice(i, 1)
  arr.splice(i + d, 0, s)
  changed()
}

async function upload(scene, kind, file) {
  error.value = ''
  try {
    await flush()
    project.value = await api.uploadScene(props.id, scene.id, kind, file)
  } catch (e) {
    error.value = e.message
  }
}

async function clear(scene, kind) {
  await flush()
  project.value = await api.clearMedia(props.id, scene.id, kind)
}

async function onJob(job) {
  error.value = ''
  await flush()
  jobs.value.unshift(job)
  activeJob.value = job.id
  watch(job)
}

function onCharacters(fresh) {
  error.value = ''
  project.value.characters = fresh.characters
  project.value.updatedAt = fresh.updatedAt
}

const refIndex = computed(() => (project.value?.scenes || []).findIndex((s) => s.id === project.value?.styleRefSceneId))
const refScene = computed(() => refIndex.value >= 0)

async function setRef(sceneId) {
  error.value = ''
  try {
    await flush()
    const fresh = await api.styleRef(props.id, sceneId)
    project.value.styleRefMediaId = fresh.styleRefMediaId
    project.value.styleRefSceneId = fresh.styleRefSceneId
  } catch (e) {
    error.value = e.message
  }
}

const jobLabel =(j) => ({ RUNNING: '⏳', DONE: '✔', FAILED: '✖' }[j.status] + ' ' + j.title)

// The chat assistant changed something: show it (and follow the jobs it started).
async function onAssistant(e) {
  const pid = e.detail?.projectId
  if (pid && pid !== '*' && pid !== props.id) return
  await flush()
  await load()
  await loadJobs()
}

onMounted(async () => {
  await load()
  await loadJobs()
  window.addEventListener('ms:refresh', onAssistant)
})
onBeforeUnmount(() => {
  window.removeEventListener('ms:refresh', onAssistant)
  if (saveTimer) save()
  watchers.forEach((es) => es.close())
})
</script>

<template>
  <div v-if="project" class="page">
    <div class="toolbar">
    <div class="row header">
      <button class="icon-only" title="Về danh sách dự án" aria-label="Về danh sách dự án" @click="router.push('/')"><Icon name="back" /></button>
      <input v-model="project.name" class="title" aria-label="Tên dự án" @input="changed" />
      <select v-model="project.aspect" style="width:120px" aria-label="Tỉ lệ khung hình" @change="changed">
        <option>16:9</option><option>9:16</option><option>1:1</option>
      </select>
      <label class="row chk"><input v-model="project.kenBurns" type="checkbox" @change="changed" /> Ken Burns</label>
      <span class="grow" />
      <span class="save" :class="{ busy: saving }"><Icon :name="saving ? 'loader' : 'check'" :size="14" />{{ saving ? 'Đang lưu…' : 'Đã lưu tự động' }}</span>
      <button class="primary" :disabled="running.some((j) => j.type === 'render')" title="Chỉ dựng lại các cảnh đã đổi"
              @click="run(() => api.render(id))"><Icon name="clapper" /> {{ running.some((j) => j.type === 'render') ? 'Đang xuất…' : 'Xuất video' }}</button>
    </div>
    <nav class="steps" role="tablist" aria-label="Các bước làm video">
      <button v-for="st in steps" :key="st.id" class="step" :class="[st.state, { active: tab === st.id }]"
              role="tab" :aria-selected="tab === st.id" :title="st.note" @click="goto(st.id)">
        <span class="dot"><Icon v-if="st.state === 'ok'" name="check" :size="12" /><template v-else>{{ st.n }}</template></span>
        <span class="stx"><b>{{ st.label }}</b><small>{{ st.note }}</small></span>
      </button>
    </nav>
    </div>
    <div v-if="error" class="error-bar">{{ error }}</div>

    <section v-if="next" class="next" :class="next.tone" aria-label="Việc nên làm tiếp">
      <div class="nx">
        <span class="kicker">Việc nên làm tiếp</span>
        <h2>{{ next.title }}</h2>
        <p>{{ next.text }}</p>
      </div>
      <div class="row nact">
        <button v-for="a in next.actions" :key="a.label" :class="{ primary: a.primary }" :disabled="a.disabled" @click="a.go()"><Icon v-if="a.icon" :name="a.icon" />{{ a.label }}</button>
      </div>
    </section>

    <div class="cols">
      <section class="left">
        <div v-show="tab === 'nguon'" id="step-nguon" class="stack">
        <div class="panel niche-bar">
          <span id="niche-lbl2" class="muted small">Ngách nội dung</span>
          <div class="seg" role="radiogroup" aria-labelledby="niche-lbl2">
            <label v-for="n in NICHES" :key="n.id" :class="{ on: sb.niche === n.id }">
              <input v-model="sb.niche" type="radio" name="niche2" :value="n.id" /><Icon :name="n.icon" :size="15" /> {{ n.label }}
            </label>
          </div>
          <p class="hint">{{ niche.hint }}</p>
        </div>
        <SourceImages :project="project" :busy="analyzing" @updated="(p) => { project.sources = p.sources }"
                      @job="onJob" @error="(m) => (error = m)" />

        <CharacterRefs :project="project" @updated="onCharacters" @error="(m) => (error = m)"
                       @portraits="(ids) => run(() => api.portraits(id, ids))" />
        </div>

        <DramaWriter v-if="tab === 'kichban' && sb.niche === 'drama'" :project="project"
                     :busy="running.some((j) => j.type.startsWith('drama') || j.type === 'storyboard')"
                     @run="(step, body) => run(() => api.drama(id, step, body))"
                     @use="(e) => { sb.topic = e.script; sb.append = false }" />

        <div v-show="tab === 'kichban'" id="step-kichban" class="panel">
          <h3>Kịch bản · nhờ Claude chia cảnh</h3>
          <div class="field">
            <label id="niche-lbl">Ngách nội dung</label>
            <div class="seg" role="radiogroup" aria-labelledby="niche-lbl">
              <label v-for="n in NICHES" :key="n.id" :class="{ on: sb.niche === n.id }">
                <input v-model="sb.niche" type="radio" name="niche" :value="n.id" /><Icon :name="n.icon" :size="15" /> {{ n.label }}
              </label>
            </div>
            <p class="hint">{{ niche.hint }}</p>
          </div>
          <div class="field">
            <label>{{ sb.niche === 'drama' ? 'Kịch bản một tập (dán nguyên văn: tiêu đề cảnh, hành động, TÊN: lời thoại)' : 'Chủ đề / mô tả video' }}</label>
            <textarea v-model="sb.topic" :rows="sb.niche === 'drama' ? 14 : 2" :placeholder="niche.placeholder" />
            <div v-if="sb.niche === 'facts' || sb.niche === 'history'" class="topic-chips" aria-label="Gợi ý chủ đề">
              <button v-for="t in (sb.niche === 'history' ? HISTORY_TOPICS : FACT_TOPICS)" :key="t" type="button" class="topic-chip small" @click="sb.topic = t">{{ t }}</button>
            </div>
          </div>
          <div class="row">
            <div style="width:90px"><label>{{ sb.niche === 'drama' ? 'Số shot' : 'Số cảnh' }}</label><input v-model.number="sb.scenes" type="number" min="1" :max="sb.niche === 'drama' ? MAX_SHOTS : 34" /></div>
            <div style="width:140px"><label>Ngôn ngữ lời thoại</label><input v-model="sb.language" /></div>
            <div class="grow"><label>Phong cách hình</label><input v-model="sb.style" placeholder="cinematic, anime, flat illustration…" /></div>
          </div>
          <div class="row" style="margin-top:10px">
            <label class="row chk" title="Nhân vật đầu to, mắt to, thân nhỏ dễ thương (2–3 đầu)"><input v-model="sb.chibi" type="checkbox" /> Nhân vật chibi dễ thương</label>
            <label class="row chk"><input v-model="sb.append" type="checkbox" /> Thêm vào cuối (không thay thế)</label>
            <span class="grow" />
            <button class="primary" :disabled="!sb.topic" @click="run(() => api.storyboard(id, sb))"><Icon name="sparkles" /> {{ sb.niche === 'drama' ? 'Chia shot' : 'Tạo kịch bản' }}</button>
          </div>
        </div>

        <details v-show="tab === 'kichban'" class="panel adv" :open="!!project.styleBible">
          <summary>Quy tắc hình ảnh · giữ nhân vật và phong cách giống nhau</summary>
          <div class="row" style="margin-top:10px">
            <span class="grow" />
            <span v-if="refScene" class="badge"><Icon name="star" :size="13" /> Tham chiếu: cảnh {{ refIndex + 1 }}</span>
            <button v-if="project.styleRefMediaId" class="small" @click="setRef(null)">Bỏ tham chiếu</button>
          </div>
          <p class="muted small">
            Đoạn này được chèn vào đầu <strong>mọi</strong> prompt hình (Canva và Flow) để giữ nhân vật và phong cách giống nhau.
            Với Canva, ảnh tham chiếu (dấu sao) được gửi kèm khi tạo các cảnh sau.
          </p>
          <textarea v-model="project.styleBible" rows="5" @input="changed"
                    placeholder="STYLE: flat illustration, pastel palette… CHARACTERS: Lan — 25-year-old Vietnamese woman, short black bob, round face, yellow raincoat…" />
          <div v-if="project.canvaFolderId" class="row muted small" style="margin-top:6px">
            <a :href="`https://www.canva.com/folder/${project.canvaFolderId}`" target="_blank" class="ih"><Icon name="folder" /> Thư mục Canva của dự án</a>
            <a v-if="project.canvaDesignId" :href="`https://www.canva.com/design/${project.canvaDesignId}/edit`" target="_blank">
              <Icon name="image" /> Storyboard trên Canva (mỗi cảnh một trang)</a>
          </div>
        </details>

        <div v-show="tab === 'amthanh'" id="step-amthanh" class="stack">
        <ProjectSound :project="project" :version="version" @changed="changed" @error="(m) => (error = m)"
                      @music="(p) => { project.musicFile = p.musicFile; project.musicStart = p.musicStart; project.updatedAt = p.updatedAt }" />
        </div>

        <div v-show="tab === 'hinh'" id="step-hinh" class="row section">
          <h3 class="grow" style="margin:0">Cảnh ({{ project.scenes.length }}) · ~{{ totalDuration.toFixed(0) }}s</h3>
          <button class="primary" :disabled="!emptyCount" title="Tạo hình cho các cảnh trống bằng Google Flow (service tạo ảnh hoặc extension, chọn trong Cài đặt)"
                  @click="run(() => api.flowExt(id))"><Icon name="flow" /> Flow ({{ emptyCount }} cảnh trống)</button>
          <button :disabled="!emptyCount" title="Gemini API: vài giây mỗi ảnh, nhiều cảnh cùng lúc (cần API key trong Cài đặt, có tính phí)"
                  @click="run(() => api.geminiAll(id, true))"><Icon name="zap" /> Gemini ({{ emptyCount }})</button>
          <button :disabled="!emptyCount" title="Chỉ tạo các cảnh đang trống hình, cùng nhân vật và phong cách"
                  @click="run(() => api.canvaAll(id, true))"><Icon name="palette" /> Canva tất cả ({{ emptyCount }} cảnh trống)</button>
          <button @click="addScene"><Icon name="plus" /> Cảnh</button>
        </div>

        <div v-show="tab === 'hinh'" class="stack">
        <div v-if="!project.scenes.length" class="panel muted">Chưa có cảnh nào. Tạo kịch bản ở tab "Kịch bản" (hoặc phân tích ở tab "Nguồn & nhân vật"), hoặc bấm "+ Cảnh".</div>
        <SceneCard
          v-for="(s, i) in project.scenes" :key="s.id"
          :scene="s" :index="i" :total="project.scenes.length" :project-id="id" :version="version"
          :busy="busyScenes.has(s.id)"
          :is-ref="project.styleRefSceneId === s.id"
          :cast="project.niche === 'drama' || castNames.length ? castNames : null"
          @ref="setRef(s.id)"
          class="scene"
          @change="changed"
          @remove="removeScene(i)"
          @move="(d) => move(i, d)"
          @flow="(t) => run(() => (t === 'image' ? api.flowExt(id, [s.id]) : api.flow(id, s.id, t)))"
          @gemini="run(() => api.gemini(id, s.id))"
          @canva="run(() => api.canva(id, s.id))"
          @upload="(k, f) => upload(s, k, f)"
          @clear="(k) => clear(s, k)"
        />
        </div>

        <div v-show="tab === 'xuat'" id="step-xuat" class="stack">
        <div class="panel">
          <h3>Xuất video</h3>
          <button class="primary wide" @click="run(() => api.render(id))"><Icon name="clapper" /> Render bằng FFmpeg</button>
          <template v-if="project.lastRender">
            <video :src="fileUrl(id, 'output', project.lastRender, version)" controls class="final" />
            <div class="muted small">{{ project.lastRender }} (+ file .srt cùng tên trong thư mục output)</div>
          </template>
        </div>

        <SlidesPanel :project="project" :version="version" :busy="running.some((j) => j.type === 'slides')"
                     @make="(t, cover) => run(() => api.slides(id, t, cover))"
                     @cover="(cover) => run(() => api.slidesCover(id, cover))"
                     @studio="(b) => run(() => api.tiktokStudioPost(id, b))"
                     @caption="run(() => api.tiktokCaption(id))" />

        <TiktokPublish :project="project" @job="onJob" @error="(m) => (error = m)" @changed="changed" />

        </div>
      </section>

      <section class="right">
        <div class="panel">
          <h3>Tác vụ</h3>
          <div class="joblist">
            <div v-for="j in jobs" :key="j.id" class="jobitem" :class="{ on: j.id === activeJob, bad: j.status === 'FAILED' }"
                 :title="j.title" @click="activeJob = j.id">
              <Icon :name="j.status === 'RUNNING' ? 'loader' : j.status === 'FAILED' ? 'fail' : 'check'" :size="14" :class="j.status === 'FAILED' ? 'err' : j.status === 'RUNNING' ? 'muted' : 'ok'" /><span class="jt">{{ j.title }}</span>
              <button v-if="j.status === 'RUNNING'" class="small danger" @click.stop="api.cancelJob(j.id)">Huỷ</button>
            </div>
            <div v-if="!jobs.length" class="muted small" style="padding:8px">Chưa có tác vụ nào.</div>
          </div>
          <JobLog :job-id="activeJob" />
        </div>

        <details class="panel adv">
          <summary>Nâng cao · ra lệnh tự do cho Claude</summary>
          <p class="muted small">Claude có quyền dùng các MCP đang bật (Playwright/Google Flow, Canva, Voicebox) trong thư mục dự án.</p>
          <textarea v-model="customPrompt" rows="3" placeholder="VD: Dùng Canva tạo thumbnail YouTube cho video này, lưu vào assets/thumbnail.png" />
          <div class="row" style="justify-content:flex-end;margin-top:8px">
            <button :disabled="!customPrompt" @click="run(() => api.agent(id, customPrompt))"><Icon name="play" /> Chạy</button>
          </div>
        </details>
      </section>
    </div>
  </div>
  <div v-else-if="error" class="error-bar">{{ error }}</div>
</template>

<style scoped>
.seg { display: flex; flex-wrap: wrap; gap: var(--s2); }
.seg label { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border: 1px solid var(--border-strong);
  border-radius: var(--r-full); cursor: pointer; font-weight: 600; font-size: 13px; color: var(--text); transition: background var(--t-fast); }
.seg label:hover { background: var(--panel-2); }
.seg label.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.seg input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.seg label:has(input:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
.niche-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.niche-bar .hint { flex-basis: 100%; margin: 0; }
.topic-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--s2); }
.topic-chip { border-radius: var(--r-full); font-weight: 500; }
/* Unified toolbar: a translucent bar that stays on top of the scrolling content, like a macOS window toolbar. */
.toolbar { position: sticky; top: 0; z-index: 20; margin: calc(-1 * var(--s5)) calc(-1 * var(--s6)) var(--s4);
  padding: 10px var(--s6) 10px; background: var(--toolbar); border-bottom: 1px solid var(--border);
  -webkit-backdrop-filter: var(--material-blur); backdrop-filter: var(--material-blur); }
.header { margin-bottom: 10px; gap: var(--s3); flex-wrap: nowrap; }
.save { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; font-weight: 500; color: var(--ok); }
.save.busy { color: var(--muted); }
.stack { display: flex; flex-direction: column; gap: 12px; }
#step-nguon, #step-kichban, #step-amthanh, #step-hinh, #step-xuat { scroll-margin-top: 12px; }

/* Segmented control: one grey track, the selected segment is a raised white pill. */
.steps { display: flex; gap: 2px; overflow-x: auto; padding: 2px; border-radius: 9px; background: var(--fill); scrollbar-width: none; }
.step { flex: 1 0 auto; display: flex; align-items: center; justify-content: center; gap: 7px; padding: 4px 12px; min-height: 30px;
        background: transparent; border-color: transparent; box-shadow: none; border-radius: 7px; color: var(--text); }
.step:hover:not(:disabled) { background: var(--fill); }
.step .dot { flex: 0 0 18px; height: 18px; border-radius: 99px; display: flex; align-items: center; justify-content: center;
             font-size: 11px; font-weight: 600; background: var(--fill-strong); color: var(--muted); }
.step .stx { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.step .stx b { font-size: 13px; font-weight: 500; }
.step .stx small { display: none; font-size: 11px; color: var(--muted); }
.step.active, .step.active:hover:not(:disabled) { background: var(--panel); box-shadow: 0 1px 3px rgba(0, 0, 0, .14), 0 0 0 .5px rgba(0, 0, 0, .06); }
.step.active .stx b { font-weight: 600; }
.step.ok .dot { background: var(--ok); color: #fff; }
.step.todo .dot { background: var(--warn-line); color: var(--on-warn); }
.step.todo .stx small { display: inline; color: var(--warn); }
@media (max-width: 1500px) { .step.todo .stx small { display: none; } }

.next { display: flex; flex-wrap: wrap; gap: 10px 20px; align-items: center; border-radius: var(--r-lg); padding: 12px 16px;
        margin-bottom: 14px; border: 1px solid color-mix(in srgb, var(--accent) 22%, transparent); background: var(--accent-soft); }
.next.warn { background: var(--warn-soft); border-color: var(--warn-line); }
.next.ok { background: var(--ok-soft); border-color: var(--ok-line); }
.next .nx { flex: 1 1 320px; min-width: 0; }
.next .kicker { font-size: 11px; font-weight: 600; color: var(--muted); }
.next.warn .kicker { color: var(--warn); }
.next h2 { margin: 1px 0 2px; font-size: 15px; }
.next p { margin: 0; font-size: 13px; line-height: 1.5; color: var(--text); }

.adv { padding: 0 20px; }
.adv > summary { min-height: 52px; display: flex; align-items: center; cursor: pointer; font-weight: 600; font-size: 14px; list-style-position: inside; }
.adv[open] { padding-bottom: 18px; }
.title { font-size: 15px; font-weight: 600; max-width: 420px; border-color: transparent; background: transparent; box-shadow: none; padding-left: 8px; }
.title:hover { border-color: var(--border); }
.title:focus { border-color: var(--accent); background: var(--panel); }
.chk { gap: 6px; font-size: 13px; color: var(--text); margin: 0; }
.chk input { width: auto; }
.cols { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.left, .right { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.section { margin-top: 0; flex-wrap: wrap; }
.right { position: sticky; top: 12px; }
.joblist { max-height: 180px; overflow: auto; border: 1px solid var(--border); border-radius: var(--r-sm); margin-bottom: 8px; background: var(--bg); }
.jobitem { display: flex; align-items: center; gap: 8px; padding: 6px 10px; cursor: pointer; border-bottom: 1px solid var(--border); font-size: 13px; color: var(--text); }
.jobitem:last-child { border-bottom: 0; }
.jobitem:hover { background: var(--panel-2); }
.jobitem.on { background: var(--accent-soft); font-weight: 600; }
.jobitem.bad .jt { color: var(--err); }
.jt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wide { width: 100%; }
.final { width: 100%; max-height: 70vh; margin-top: 10px; border-radius: 8px; background: #000; }
.small { font-size: 12px; }
code { background: var(--bg); padding: 1px 5px; border-radius: 4px; }
@media (max-width: 1100px) { .cols { grid-template-columns: minmax(0, 1fr); } .right { position: static; } }
@media (max-width: 820px) {
  .toolbar { position: static; margin: 0 0 var(--s3); padding: 0; background: transparent; border: 0; backdrop-filter: none; }
  .header { gap: 6px; flex-wrap: wrap; }
  .step { flex: 1 0 auto; min-height: 44px; }
  .title { flex: 1 1 100%; max-width: none; order: 1; }
  .header :deep(select) { width: auto !important; }
}
</style>
