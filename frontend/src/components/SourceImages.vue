<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api, fileUrl } from '../api'
import { ask } from '../confirm'

const props = defineProps({ project: Object, busy: Boolean })
const emit = defineEmits(['updated', 'job', 'error'])

const note = ref('')
// Tool + model for this run, remembered per tool in this browser.
const MODELS = {
  claude: ['opus', 'sonnet', 'haiku'],
  agy: ['gemini-3.8-flash-high', 'gemini-3.8-flash-medium', 'gemini-3.8-flash-low',
        'claude-sonnet-4-6', 'claude-opus-4-6-thinking', 'gpt-oss-120b-medium'],
}
const DEFAULT_MODEL = { claude: 'opus', agy: 'gemini-3.8-flash-high' }
function load(k, d) {
  try { return localStorage.getItem(k) || d } catch { return d }
}
function store(k, v) {
  try { localStorage.setItem(k, v) } catch { /* ignore */ }
}
const ai = ref(load('ms.analyze.ai', 'claude'))
const model = ref(load('ms.analyze.model.' + ai.value, DEFAULT_MODEL[ai.value]))
function onAi() {
  store('ms.analyze.ai', ai.value)
  model.value = load('ms.analyze.model.' + ai.value, DEFAULT_MODEL[ai.value])
}
function onModel() {
  store('ms.analyze.model.' + ai.value, model.value)
}
const uploading = ref(0)
const dragging = ref(false)
const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' }

async function upload(files) {
  const ok = files.filter((f) => EXT[f.type])
  if (ok.length < files.length) emit('error', 'Bỏ qua file không phải ảnh PNG, JPG hoặc WebP')
  if (!ok.length) return
  uploading.value += ok.length
  try {
    emit('updated', await api.addSources(props.project.id, ok))
  } catch (e) {
    emit('error', e.message)
  } finally {
    uploading.value -= ok.length
  }
}

function pick() {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = 'image/png,image/jpeg,image/webp'
  input.onchange = () => upload([...input.files])
  input.click()
}

// Ctrl+V anywhere on the project page adds the clipboard image(s) here, unless the pointer is over the
// character panel (which takes its own pastes). Text pastes are ignored.
function onPaste(e) {
  if (document.querySelector('.character-panel:hover')) return
  const blobs = [...(e.clipboardData?.items || [])]
    .filter((i) => i.kind === 'file' && i.type.startsWith('image/'))
    .map((i) => i.getAsFile()).filter(Boolean)
  if (!blobs.length) return
  // Copying from Word / a web page puts both text and a picture of it on the clipboard: in a text field, keep the text.
  const inField = e.target?.closest?.('textarea, input')
  if (inField && e.clipboardData.getData('text/plain')) return
  e.preventDefault()
  upload(blobs.map((b, i) => {
    const type = EXT[b.type] ? b.type : 'image/png'
    return new File([b], b.name && b.name !== 'image.png' ? b.name : `paste-${Date.now()}-${i}.${EXT[type]}`, { type })
  }))
}

function onDrop(e) {
  dragging.value = false
  upload([...(e.dataTransfer?.files || [])].filter((f) => f.type.startsWith('image/')))
}

async function remove(name) {
  try {
    emit('updated', await api.deleteSource(props.project.id, name))
  } catch (e) {
    emit('error', e.message)
  }
}

const canRun = computed(() => !!props.project.sources?.length || !!note.value.trim())

async function analyze(storyboard) {
  if (storyboard && props.project.scenes.some((s) => s.mediaFile || s.audioFile) &&
      !(await ask({ title: 'Thay kịch bản hiện tại?', message: 'Kịch bản mới sẽ thay toàn bộ cảnh hiện tại, kể cả hình đã tạo.',
        confirmLabel: 'Thay kịch bản', danger: true, icon: 'alert' }))) return
  try {
    emit('job', await api.analyze(props.project.id, note.value, storyboard, ai.value, model.value.trim()))
  } catch (e) {
    emit('error', e.message)
  }
}

onMounted(() => window.addEventListener('paste', onPaste))
onBeforeUnmount(() => window.removeEventListener('paste', onPaste))
</script>

<template>
  <div class="panel source-panel" :class="{ dragging }"
       @dragover.prevent="dragging = true" @dragleave.self="dragging = false" @drop.prevent="onDrop">
    <div class="row">
      <h3 class="grow" style="margin:0"><Icon name="sparkles" />Bắt đầu từ nội dung hoặc hình ảnh</h3>
      <span class="muted small">gõ hoặc dán chữ · Ctrl+V để dán ảnh · kéo thả nhiều ảnh</span>
      <button class="small" @click="pick">+ Chọn ảnh</button>
    </div>
    <p class="muted small">
      Nhập nội dung (truyện, bài thơ, dàn ý, danh sách cảnh, ghi chú…) và / hoặc dán hình liên quan (trang sách, tranh minh
      hoạ, ảnh sản phẩm…) vào cùng ô bên dưới. Chỉ cần một trong hai. Claude sẽ đọc, nhận diện nhân vật (có hình thì tự cắt
      ảnh làm tham chiếu), chọn phong cách và số cảnh rồi viết kịch bản. Thơ hoặc truyện được giữ nguyên văn.
    </p>

    <div class="composer">
    <div v-if="project.sources?.length || uploading" class="thumbs">
      <div v-for="s in project.sources" :key="s" class="thumb">
        <img :src="fileUrl(project.id, 'assets', s)" />
        <button class="x icon-only" title="Bỏ ảnh này" aria-label="Bỏ ảnh này" @click="remove(s)"><Icon name="x" /></button>
      </div>
      <div v-for="n in uploading" :key="'u' + n" class="thumb loading"><span class="spin" /></div>
    </div>
    <textarea v-model="note" rows="6"
              placeholder="Nhập hoặc dán nội dung: truyện, bài thơ, dàn ý các cảnh, ghi chú (ví dụ: làm cho bé 5 tuổi, giọng vui tươi, khoảng 1 phút)… Dán hình (Ctrl+V) hoặc kéo thả hình vào đây cũng được." />
    </div>
    <div class="row" style="margin-top:8px">
      <label class="small muted">Công cụ</label>
      <select v-model="ai" style="width:auto" @change="onAi">
        <option value="claude">Claude</option>
        <option value="agy">agy (Antigravity)</option>
      </select>
      <label class="small muted">Model</label>
      <input v-model="model" list="analyze-models" style="width:230px" placeholder="để trống = mặc định" @change="onModel" />
      <datalist id="analyze-models">
        <option v-for="m in MODELS[ai]" :key="m" :value="m" />
      </datalist>
    </div>
    <div v-if="ai === 'agy'" class="small" style="color:var(--warn);margin-top:4px">
      <Icon name="alert" /> agy không nhìn được nội dung ảnh, nên có thể đọc sai chữ và nhân vật. Chọn Claude nếu cần chính xác.
    </div>
    <div class="row" style="margin-top:8px">
      <span class="muted small grow">{{ project.sources?.length || 0 }} ảnh · {{ note.trim().length }} ký tự</span>
      <button :disabled="busy || !canRun" @click="analyze(false)">Chỉ phân tích nhân vật</button>
      <button class="primary" :disabled="busy || !canRun" @click="analyze(true)">
        <Icon name="sparkles" /> Phân tích & tạo kịch bản
      </button>
    </div>
  </div>
</template>

<style scoped>
.source-panel { border-color: color-mix(in srgb, var(--accent) 50%, var(--border)); }
.source-panel.dragging { border-color: var(--accent); border-style: dashed; }
.composer { border: 1px solid var(--border); border-radius: 10px; background: var(--bg); padding: 8px; }
.composer:focus-within { border-color: var(--accent); }
.composer textarea { display: block; width: 100%; border: 0; outline: 0; background: transparent; padding: 4px; min-height: 120px;
                     box-shadow: none; resize: vertical; }
.composer .thumbs { margin-bottom: 8px; }
.thumbs { display: grid; grid-template-columns: repeat(auto-fill, minmax(90px, 110px)); gap: 8px; }
.thumb { position: relative; aspect-ratio: 3/4; background: var(--bg); border-radius: 8px; overflow: hidden;
         border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.x { position: absolute; top: 4px; right: 4px; padding: 0 6px; font-size: 11px; background: rgba(0,0,0,.6); }
.drop { border: 1px dashed var(--border); border-radius: 8px; padding: 22px; text-align: center; cursor: pointer; }
.drop:hover { border-color: var(--accent); }
.spin { width: 18px; height: 18px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%;
        animation: s .8s linear infinite; }
@keyframes s { to { transform: rotate(360deg); } }
.small { font-size: 12px; }
</style>
