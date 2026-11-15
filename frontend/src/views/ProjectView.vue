<script setup>
import { ref, computed, watch as vueWatch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, fileUrl } from '../api'
import SceneCard from '../components/SceneCard.vue'
import JobLog from '../components/JobLog.vue'
import CharacterRefs from '../components/CharacterRefs.vue'
import TiktokPublish from '../components/TiktokPublish.vue'
import SourceImages from '../components/SourceImages.vue'
import ProjectVoice from '../components/ProjectVoice.vue'
import ProjectSound from '../components/ProjectSound.vue'
import SlidesPanel from '../components/SlidesPanel.vue'
import TimelineEditor from '../components/TimelineEditor.vue'

const props = defineProps({ id: String })
const router = useRouter()

const project = ref(null)
const error = ref('')
const jobs = ref([])
const activeJob = ref(null)
const saving = ref(false)
const customPrompt = ref('')
const sb = ref({ topic: '', scenes: 6, language: 'Vietnamese', style: '', append: false, niche: 'story' })
const NICHES = [
  { id: 'story', label: 'Truyện · cổ tích', icon: 'file', hint: 'Có nhân vật xuyên suốt, cảnh mở đầu giới thiệu và cảnh cuối nêu bài học.',
    placeholder: 'VD: Sự tích cây vú sữa, giọng kể ấm áp' },
  { id: 'facts', label: 'Bạn có biết? · khoa học, vũ trụ', icon: 'sparkles', hint: 'Cảnh 1 là câu mở đầu gây tò mò, mỗi cảnh giữa là một sự thật có thật (càng về sau càng bất ngờ), cảnh cuối hỏi người xem để kéo bình luận. Không cần nhân vật.',
    placeholder: 'VD: 5 sự thật đáng sợ về hố đen, giọng kể hào hứng' },
]
const FACT_TOPICS = ['5 sự thật khó tin về vũ trụ', 'Điều gì xảy ra nếu Mặt Trời biến mất?', 'Sự thật về hố đen', 'Những loài vật kỳ lạ dưới đáy đại dương', 'Cơ thể người có những bí mật gì?', 'Sao Kim: hành tinh địa ngục', 'Nếu Trái Đất ngừng quay', 'Những kỷ lục tự nhiên đáng kinh ngạc']
const niche = computed(() => NICHES.find((n) => n.id === sb.value.niche) || NICHES[0])
// a facts video = hook + 5 facts + closing question
vueWatch(() => sb.value.niche, (n, old) => { if (old && n === 'facts' && sb.value.scenes === 6) sb.value.scenes = 7 })

let saveTimer = null
const watchers = new Map()

const version = computed(() => project.value?.updatedAt || '')
const running = computed(() => jobs.value.filter((j) => j.status === 'RUNNING'))
const analyzing = computed(() => running.value.some((j) => j.type === 'analyze' || j.type === 'storyboard'))
// Scenes without a picture (and with an image prompt): the only ones "Flow tất cả" / "Canva tất cả" make.
const emptyCount = computed(() => (project.value?.scenes || []).filter((s) => !s.mediaFile && (s.imagePrompt || '').trim()).length)
const scenesAll = computed(() => project.value?.scenes || [])
const missingCount = computed(() => scenesAll.value.filter((s) => !s.mediaFile).length)
const voiceMissing = computed(() => scenesAll.value.filter((s) => (s.narration || '').trim() && !s.audioFile).length)
const lastFlowFailed = computed(() => { const j = jobs.value.find((x) => /flow/i.test(x.title || '')); return !!j && j.status === 'FAILED' })

// The workflow as six steps; each shows where the project stands so the next move is obvious.
const steps = computed(() => {
  const p = project.value
  if (!p) return []
  const n = scenesAll.value.length
  const hasAudio = p.scriptMode ? !!p.musicFile : voiceMissing.value === 0 && n > 0
  return [
    { id: 'nguon', n: 1, label: 'Nguồn & nhân vật', note: (p.sources || []).length ? `${p.sources.length} ảnh nguồn` : 'Không bắt buộc', state: 'idle' },
    { id: 'kichban', n: 2, label: 'Kịch bản', note: n ? `${n} cảnh` : 'Chưa có cảnh', state: n ? 'ok' : 'todo' },
    { id: 'amthanh', n: 3, label: 'Âm thanh', note: p.scriptMode ? (p.musicFile ? 'Có nhạc nền' : 'Chưa có nhạc nền') : (!n ? '—' : voiceMissing.value ? `Thiếu giọng ${voiceMissing.value} cảnh` : 'Đủ giọng đọc'), state: !n ? 'idle' : hasAudio ? 'ok' : p.scriptMode ? 'idle' : 'todo' },
    { id: 'dung', n: 4, label: 'Dựng video', note: 'Timeline & chỉnh sửa', state: 'idle' },
    { id: 'hinh', n: 5, label: 'Cảnh & hình ảnh', note: !n ? '—' : missingCount.value ? `Còn ${missingCount.value} cảnh trống` : 'Đủ hình', state: !n ? 'idle' : missingCount.value ? 'todo' : 'ok' },
    { id: 'xuat', n: 6, label: 'Xuất & đăng', note: p.lastRender ? 'Đã xuất' : 'Chưa xuất', state: p.lastRender ? 'ok' : 'idle' },
  ]
})

function goto(id) {
  document.getElementById('step-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
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
  if (!project.value.scriptMode && voiceMissing.value) {
    return { tone: 'info', title: `${voiceMissing.value} cảnh chưa có giọng đọc`, text: 'Tạo giọng đọc để video có tiếng và độ dài cảnh khớp với lời thoại.',
      actions: [{ label: 'Tạo giọng tất cả', primary: true, go: () => run(() => api.voice(props.id, {})) }] }
  }
  if (!project.value.lastRender) {
    return { tone: 'ok', title: 'Mọi thứ đã sẵn sàng', text: 'Xuất video. Những lần sau chỉ dựng lại các cảnh bạn vừa sửa nên nhanh hơn.',
      actions: [{ label: 'Xuất video', primary: true, go: () => run(() => api.render(props.id)) }, { label: 'Xem timeline', go: () => goto('dung') }] }
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

function removeScene(i) {
  if (!confirm(`Xoá cảnh ${i + 1}?`)) return
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

onMounted(async () => {
  await load()
  await loadJobs()
})
onBeforeUnmount(() => {
  if (saveTimer) save()
  watchers.forEach((es) => es.close())
})
</script>

<template>
  <div v-if="project" class="page">
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
    <div v-if="error" class="error-bar">{{ error }}</div>

    <nav class="steps" aria-label="Các bước làm video">
      <button v-for="st in steps" :key="st.id" class="step" :class="st.state" @click="goto(st.id)">
        <span class="dot"><Icon v-if="st.state === 'ok'" name="check" :size="14" /><template v-else>{{ st.n }}</template></span>
        <span class="stx"><b>{{ st.label }}</b><small>{{ st.note }}</small></span>
      </button>
    </nav>

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
        <div id="step-nguon" class="stack">
        <SourceImages :project="project" :busy="analyzing" @updated="(p) => { project.sources = p.sources }"
                      @job="onJob" @error="(m) => (error = m)" />

        <CharacterRefs :project="project" @updated="onCharacters" @error="(m) => (error = m)" />
        </div>

        <div id="step-kichban" class="panel">
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
            <label>Chủ đề / mô tả video</label>
            <textarea v-model="sb.topic" rows="2" :placeholder="niche.placeholder" />
            <div v-if="sb.niche === 'facts'" class="topic-chips" aria-label="Gợi ý chủ đề">
              <button v-for="t in FACT_TOPICS" :key="t" type="button" class="topic-chip small" @click="sb.topic = t">{{ t }}</button>
            </div>
          </div>
          <div class="row">
            <div style="width:90px"><label>Số cảnh</label><input v-model.number="sb.scenes" type="number" min="1" max="40" /></div>
            <div style="width:140px"><label>Ngôn ngữ lời thoại</label><input v-model="sb.language" /></div>
            <div class="grow"><label>Phong cách hình</label><input v-model="sb.style" placeholder="cinematic, anime, flat illustration…" /></div>
          </div>
          <div class="row" style="margin-top:10px">
            <label class="row chk"><input v-model="sb.append" type="checkbox" /> Thêm vào cuối (không thay thế)</label>
            <span class="grow" />
            <button class="primary" :disabled="!sb.topic" @click="run(() => api.storyboard(id, sb))"><Icon name="sparkles" /> Tạo kịch bản</button>
          </div>
        </div>

        <details class="panel adv" :open="!!project.styleBible">
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

        <div id="step-amthanh" class="stack">
        <ProjectSound :project="project" :version="version" @changed="changed" @error="(m) => (error = m)"
                      @music="(p) => { project.musicFile = p.musicFile; project.musicStart = p.musicStart; project.updatedAt = p.updatedAt }" />

        <ProjectVoice v-if="!project.scriptMode" :project="project" @changed="changed" />
        </div>

        <div id="step-dung"><TimelineEditor :id="id" :project="project" :saving="saving" :flush="flush"
                        :render-busy="running.some((j) => j.type === 'render')"
                        @changed="changed" @replace="(p) => (project = p)" @error="(m) => (error = m)"
                        @render="run(() => api.render(id))" /></div>

        <div id="step-hinh" class="row section">
          <h3 class="grow" style="margin:0">Cảnh ({{ project.scenes.length }}) · ~{{ totalDuration.toFixed(0) }}s</h3>
          <button v-if="!project.scriptMode" @click="run(() => api.voice(id, {}))"><Icon name="mic" /> Tạo giọng tất cả</button>
          <button class="primary" :disabled="!emptyCount" title="Tạo hình cho các cảnh trống bằng Google Flow (service tạo ảnh hoặc extension, chọn trong Cài đặt)"
                  @click="run(() => api.flowExt(id))"><Icon name="flow" /> Flow ({{ emptyCount }} cảnh trống)</button>
          <button :disabled="!emptyCount" title="Gemini API: vài giây mỗi ảnh, nhiều cảnh cùng lúc (cần API key trong Cài đặt, có tính phí)"
                  @click="run(() => api.geminiAll(id, true))"><Icon name="zap" /> Gemini ({{ emptyCount }})</button>
          <button :disabled="!emptyCount" title="Chỉ tạo các cảnh đang trống hình, cùng nhân vật và phong cách"
                  @click="run(() => api.canvaAll(id, true))"><Icon name="palette" /> Canva tất cả ({{ emptyCount }} cảnh trống)</button>
          <button @click="addScene"><Icon name="plus" /> Cảnh</button>
        </div>

        <SceneCard
          v-for="(s, i) in project.scenes" :key="s.id"
          :scene="s" :index="i" :total="project.scenes.length" :project-id="id" :version="version"
          :busy="busyScenes.has(s.id)"
          :is-ref="project.styleRefSceneId === s.id"
          :script-mode="project.scriptMode"
          @ref="setRef(s.id)"
          class="scene"
          @change="changed"
          @remove="removeScene(i)"
          @move="(d) => move(i, d)"
          @flow="(t) => run(() => (t === 'image' ? api.flowExt(id, [s.id]) : api.flow(id, s.id, t)))"
          @gemini="run(() => api.gemini(id, s.id))"
          @canva="run(() => api.canva(id, s.id))"
          @voice="run(() => api.voice(id, { sceneId: s.id }))"
          @upload="(k, f) => upload(s, k, f)"
          @clear="(k) => clear(s, k)"
        />
      </section>

      <section id="step-xuat" class="right">
        <div class="panel">
          <h3>Xuất video</h3>
          <button class="primary wide" @click="run(() => api.render(id))"><Icon name="clapper" /> Render bằng FFmpeg</button>
          <template v-if="project.lastRender">
            <video :src="fileUrl(id, 'output', project.lastRender, version)" controls class="final" />
            <div class="muted small">{{ project.lastRender }} (+ file .srt cùng tên trong thư mục output)</div>
          </template>
        </div>

        <SlidesPanel :project="project" :version="version" :busy="running.some((j) => j.type === 'slides')"
                     @make="(t, cover) => run(() => api.slides(id, t, cover))" />

        <TiktokPublish :project="project" @job="onJob" @error="(m) => (error = m)" @changed="changed" />

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
.topic-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--s2); }
.topic-chip { border-radius: var(--r-full); font-weight: 500; }
.header { margin-bottom: var(--s4); gap: var(--s3); }
.save { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; font-weight: 500; color: var(--ok); }
.save.busy { color: var(--muted); }
.stack { display: flex; flex-direction: column; gap: 12px; }
#step-nguon, #step-kichban, #step-amthanh, #step-dung, #step-hinh, #step-xuat { scroll-margin-top: 12px; }

.steps { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 6px; margin-bottom: 14px; scrollbar-width: thin; }
.step { flex: 1 0 150px; display: flex; align-items: center; gap: 10px; text-align: left; padding: 8px 12px; min-height: 52px;
        background: var(--panel); border-radius: 12px; white-space: normal; }
.step .dot { flex: 0 0 28px; height: 28px; border-radius: 99px; display: flex; align-items: center; justify-content: center;
             font-size: 13px; font-weight: 700; background: var(--panel-2); color: var(--text); }
.step .stx { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; }
.step .stx b { font-size: 13px; font-weight: 600; }
.step .stx small { font-size: 12px; color: var(--muted); }
.step.ok .dot { background: var(--ok); color: var(--on-accent); }
.step.todo { background: var(--warn-soft); border-color: var(--warn-line); }
.step.todo .dot { background: var(--warn-line); color: var(--on-warn); }
.step.todo small { color: var(--warn); }

.next { display: flex; flex-wrap: wrap; gap: 14px 24px; align-items: center; border-radius: 16px; padding: 18px 22px;
        margin-bottom: 16px; border: 1px solid var(--border); background: var(--accent-soft); }
.next.warn { background: var(--warn-soft); border-color: var(--warn-line); }
.next.ok { background: var(--ok-soft); border-color: var(--ok-line); }
.next .nx { flex: 1 1 320px; min-width: 0; }
.next .kicker { font-size: 12px; font-weight: 600; letter-spacing: .04em; text-transform: uppercase; color: var(--muted); }
.next.warn .kicker { color: var(--warn); }
.next h2 { margin: 2px 0 4px; font-size: 18px; }
.next p { margin: 0; font-size: 14px; line-height: 1.55; color: var(--text); }
.nact button { min-height: 44px; }

.adv { padding: 0 20px; }
.adv > summary { min-height: 52px; display: flex; align-items: center; cursor: pointer; font-weight: 600; font-size: 14px; list-style-position: inside; }
.adv[open] { padding-bottom: 18px; }
.title { font-size: 20px; font-weight: 700; max-width: 460px; border-color: transparent; background: transparent; box-shadow: none; padding-left: 8px; }
.title:hover { border-color: var(--border); }
.title:focus { border-color: var(--accent); background: var(--panel); }
.chk { gap: 6px; font-size: 13px; color: var(--text); margin: 0; }
.chk input { width: auto; }
.cols { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.left, .right { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.section { margin-top: 8px; }
.joblist { max-height: 180px; overflow: auto; border: 1px solid var(--border); border-radius: var(--r-sm); margin-bottom: 8px; background: var(--bg); }
.jobitem { display: flex; align-items: center; gap: 8px; padding: 6px 10px; cursor: pointer; border-bottom: 1px solid var(--border); font-size: 13px; color: var(--text); }
.jobitem:last-child { border-bottom: 0; }
.jobitem:hover { background: var(--panel-2); }
.jobitem.on { background: var(--accent-soft); font-weight: 600; }
.jobitem.bad .jt { color: var(--err); }
.jt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wide { width: 100%; }
.final { width: 100%; margin-top: 10px; border-radius: 8px; background: #000; }
.small { font-size: 12px; }
code { background: var(--bg); padding: 1px 5px; border-radius: 4px; }
@media (max-width: 1100px) { .cols { grid-template-columns: minmax(0, 1fr); } .right { position: static; } }
@media (max-width: 820px) {
  .header { gap: 6px; }
  .title { flex: 1 1 100%; max-width: none; order: 1; }
  .header :deep(select) { width: auto !important; }
}
</style>
