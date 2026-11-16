<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { api, fileUrl, isVideo } from '../api'
import { ask } from '../confirm'

/**
 * Timeline editor embedded in the project page: scenes, script text, voice and music on one time axis with a live
 * preview. The project object is owned by the parent (one copy, one save path); edits call `changed`, and
 * "Xuất video" only rebuilds the scenes that changed (the server caches every finished scene).
 */
const props = defineProps({
  id: String,
  project: Object,
  saving: Boolean,
  flush: Function,
  renderBusy: Boolean,
})
const emit = defineEmits(['changed', 'replace', 'render', 'error'])

// Reads the parent's project; assigning a fresh server copy hands it back to the parent.
const project = computed({ get: () => props.project, set: (v) => emit('replace', v) })
const error = { set value(m) { emit('error', m) }, get value() { return '' } }
const sel = ref(null)
const t = ref(0)
const playing = ref(false)
const zoom = ref(60) // pixels per second
const busyVoice = ref(new Set())
const bust = ref({})
const dragId = ref(null)
const dragDx = ref(0)

const root = ref(null)
const audioEl = ref(null)
const musicEl = ref(null)
const musicLen = ref(0)

// ---------- timing (same rules as the server) ----------
const words = (s) => (s.narration || '').trim().split(/\s+/).filter(Boolean).length
function dur(s, ignoreCustom = false) {
  const p = project.value
  const fixed = s.duration > 0 ? s.duration : 4
  const voice = !p.scriptMode && s.audioFile && s.audioDuration
  const w = words(s)
  const auto = voice ? s.audioDuration + 0.3 : p.scriptMode ? (w ? Math.max(2, w / 3) : fixed) : fixed
  if (!ignoreCustom && s.customDuration > 0.2) return voice ? Math.max(s.customDuration, s.audioDuration + 0.3) : s.customDuration
  return auto
}
const autoDur = (s) => dur(s, true)

const items = computed(() => {
  let at = 0
  return (project.value?.scenes || []).map((s, i) => {
    const d = dur(s)
    const it = { s, i, start: at, d, end: at + d }
    at += d
    return it
  })
})
const total = computed(() => items.value.length ? items.value[items.value.length - 1].end : 0)
const curIdx = computed(() => {
  const arr = items.value
  if (!arr.length) return -1
  const k = arr.findIndex((x) => t.value < x.end - 1e-6)
  return k < 0 ? arr.length - 1 : k
})
const cur = computed(() => items.value[curIdx.value]?.s || null)
const selected = computed(() => project.value?.scenes.find((s) => s.id === sel.value) || null)
const selItem = computed(() => items.value.find((x) => x.s.id === sel.value) || null)

const aspect = computed(() => {
  const a = project.value?.aspect || '16:9'
  const [w, h] = a.split(':').map(Number)
  return { w, h }
})

const fmt = (x) => `${String(Math.floor(x / 60)).padStart(2, '0')}:${(x % 60).toFixed(1).padStart(4, '0')}`

// ---------- urls ----------
const media = (s) => s.mediaFile && fileUrl(props.id, 'assets', s.mediaFile, bust.value[s.id] || '')
const audio = (s) => s.audioFile && fileUrl(props.id, 'assets', s.audioFile, bust.value[s.id] || '')
const hash = (str) => { let h = 0; for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) | 0; return h }
function overlayUrl(s, i) {
  if (!project.value.scriptMode || !(s.narration || '').trim()) return null
  const k = hash([s.narration, s.mediaFile, bust.value[s.id], project.value.aspect, i === project.value.scenes.length - 1].join('|'))
  return `/api/projects/${props.id}/scenes/${s.id}/overlay.png?k=${k}`
}
const musicUrl = computed(() => project.value?.musicFile && fileUrl(props.id, 'assets', project.value.musicFile, ''))

// ---------- save (handled by the parent) ----------
const changed = () => emit('changed')
async function flush() { if (props.flush) await props.flush() }
function mergeMedia(fresh) {
  for (const s of project.value.scenes) {
    const f = fresh.scenes.find((x) => x.id === s.id)
    if (f) Object.assign(s, { mediaFile: f.mediaFile, mediaSource: f.mediaSource, audioFile: f.audioFile, audioDuration: f.audioDuration })
  }
}

// ---------- scene operations ----------
function select(id) { sel.value = id }
function seekTo(sec) {
  t.value = Math.max(0, Math.min(total.value, sec))
  if (playing.value) syncMedia()
}
function selectAndSeek(it) {
  select(it.s.id)
  seekTo(it.start)
}

function addScene(afterIndex) {
  const s = { id: Math.random().toString(16).slice(2, 10), narration: '', imagePrompt: '', duration: 4, customDuration: null }
  const arr = project.value.scenes
  arr.splice((afterIndex ?? arr.length - 1) + 1, 0, s)
  sel.value = s.id
  changed()
}
async function duplicate(s) {
  try {
    await flush()
    project.value = await api.post(`/api/projects/${props.id}/scenes/${s.id}/duplicate`)
  } catch (e) { error.value = e.message }
}
async function remove(s) {
  const arr = project.value.scenes
  if (!(await ask({ title: 'Xoá cảnh này?', message: 'Lời thoại, hình và giọng đọc của cảnh sẽ bị xoá.', confirmLabel: 'Xoá', danger: true }))) return
  const i = arr.findIndex((x) => x.id === s.id)
  arr.splice(i, 1)
  sel.value = arr[Math.min(i, arr.length - 1)]?.id || null
  t.value = Math.min(t.value, total.value)
  changed()
}
function move(s, d) {
  const arr = project.value.scenes
  const i = arr.findIndex((x) => x.id === s.id)
  const j = i + d
  if (j < 0 || j >= arr.length) return
  const [x] = arr.splice(i, 1)
  arr.splice(j, 0, x)
  changed()
}
async function replaceMedia(s) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*,video/*'
  input.onchange = async () => {
    if (!input.files[0]) return
    try {
      await flush()
      project.value = await api.uploadScene(props.id, s.id, 'media', input.files[0])
      bust.value = { ...bust.value, [s.id]: Date.now() }
    } catch (e) { error.value = e.message }
  }
  input.click()
}
async function uploadAudio(s) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'audio/*'
  input.onchange = async () => {
    if (!input.files[0]) return
    try {
      await flush()
      project.value = await api.uploadScene(props.id, s.id, 'audio', input.files[0])
      bust.value = { ...bust.value, [s.id]: Date.now() }
    } catch (e) { error.value = e.message }
  }
  input.click()
}
async function clearKind(s, kind) {
  await flush()
  project.value = await api.clearMedia(props.id, s.id, kind)
}
async function makeVoice(s) {
  try {
    await flush()
    const job = await api.voice(props.id, { sceneId: s.id })
    busyVoice.value = new Set([...busyVoice.value, s.id])
    const es = new EventSource(`/api/jobs/${job.id}/stream`)
    const done = async () => {
      es.close()
      const n = new Set(busyVoice.value); n.delete(s.id); busyVoice.value = n
      const fresh = await api.project(props.id)
      mergeMedia(fresh)
      bust.value = { ...bust.value, [s.id]: Date.now() }
    }
    es.addEventListener('status', done)
    es.onerror = done
  } catch (e) { error.value = e.message }
}
const customSet = (s, v) => {
  const n = Number(v)
  s.customDuration = n > 0.2 ? Math.round(n * 10) / 10 : null
  changed()
}

// ---------- timeline mouse: resize edge / reorder ----------
function startResize(e, it) {
  e.stopPropagation()
  e.preventDefault()
  const x0 = e.clientX
  const d0 = it.d
  const move = (ev) => {
    const nd = Math.max(0.5, Math.round((d0 + (ev.clientX - x0) / zoom.value) * 10) / 10)
    it.s.customDuration = nd
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    changed()
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function startDrag(e, it) {
  if (e.button !== 0) return
  const x0 = e.clientX
  let moved = false
  const move = (ev) => {
    const dx = ev.clientX - x0
    if (!moved && Math.abs(dx) < 6) return
    moved = true
    dragId.value = it.s.id
    dragDx.value = dx
  }
  const up = (ev) => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    if (!moved) {
      selectAndSeek(it)
    } else {
      const center = (it.start + it.d / 2) * zoom.value + (ev.clientX - x0)
      const others = items.value.filter((x) => x.s.id !== it.s.id)
      const pos = others.filter((x) => (x.start + x.d / 2) * zoom.value < center).length
      const arr = project.value.scenes
      const from = arr.findIndex((x) => x.id === it.s.id)
      if (pos !== from) {
        const [x] = arr.splice(from, 1)
        arr.splice(pos, 0, x)
        changed()
      }
    }
    dragId.value = null
    dragDx.value = 0
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function onRulerClick(e) {
  const r = e.currentTarget.getBoundingClientRect()
  const sec = (e.clientX - r.left) / zoom.value
  seekTo(sec)
  const it = items.value[curIdx.value]
  if (it) sel.value = it.s.id
}

// ---------- playback ----------
let raf = 0
let last = 0
function syncMedia() {
  const a = audioEl.value
  const m = musicEl.value
  const it = items.value[curIdx.value]
  if (a) {
    a.pause()
    if (it && !project.value.scriptMode && audio(it.s)) {
      a.src = audio(it.s)
      a.currentTime = Math.max(0, t.value - it.start)
      a.play().catch(() => {})
    }
  }
  if (m && musicUrl.value) {
    const start = project.value.musicStart || 0
    const len = musicLen.value || 0
    const pos = start + t.value
    m.volume = Math.max(0, Math.min(1, project.value.musicVolume ?? 0.25))
    m.currentTime = len > start + 1 ? start + ((t.value) % (len - start)) : pos
    m.play().catch(() => {})
  }
}
function play() {
  if (!items.value.length) return
  if (t.value >= total.value - 0.05) t.value = 0
  playing.value = true
  last = performance.now()
  syncMedia()
  raf = requestAnimationFrame(tick)
}
function stop() {
  playing.value = false
  cancelAnimationFrame(raf)
  audioEl.value?.pause()
  musicEl.value?.pause()
}
function tick(now) {
  const dt = (now - last) / 1000
  last = now
  t.value = Math.min(total.value, t.value + dt)
  if (t.value >= total.value) { stop(); return }
  raf = requestAnimationFrame(tick)
}
const toggle = () => (playing.value ? stop() : play())
// moving into another scene while playing: switch the voice-over
watch(curIdx, (i, o) => {
  if (!playing.value || i === o || i < 0) return
  sel.value = items.value[i].s.id
  const a = audioEl.value
  const it = items.value[i]
  if (a) {
    a.pause()
    if (!project.value.scriptMode && audio(it.s)) { a.src = audio(it.s); a.currentTime = 0; a.play().catch(() => {}) }
  }
})
function onKey(e) {
  const tag = (e.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return
  if (e.code === 'Space' && root.value?.matches(':hover, :focus-within')) { e.preventDefault(); toggle() }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  stop()
  window.removeEventListener('keydown', onKey)
})
// keep a valid selection when scenes are added/removed (also from the scene list below)
watch(() => project.value?.scenes.map((x) => x.id).join(), () => {
  const arr = project.value?.scenes || []
  if (!arr.some((x) => x.id === sel.value)) sel.value = arr[0]?.id || null
}, { immediate: true })

const ticks = computed(() => {
  const step = zoom.value >= 40 ? 1 : zoom.value >= 20 ? 2 : 5
  const out = []
  for (let s = 0; s <= Math.ceil(total.value); s += step) out.push(s)
  return out
})
const width = computed(() => Math.max(300, total.value * zoom.value + 60))
</script>

<template>
  <div v-if="project" ref="root" class="editor panel">
    <div class="row top">
      <h3 class="grow name" style="margin:0"><Icon name="film" />Timeline · {{ items.length }} cảnh · {{ fmt(total) }}</h3>
      <span class="badge"><Icon :name="project.scriptMode ? 'file' : 'mic'" :size="13" />{{ project.scriptMode ? 'Script' : 'Giọng đọc' }}</span>
      <span class="muted small">{{ saving ? 'Đang lưu…' : 'Đã lưu' }}</span>
      <button class="primary" :disabled="renderBusy" title="Chỉ dựng lại các cảnh đã đổi"
              @click="flush().then(() => emit('render'))"><Icon name="clapper" /> {{ renderBusy ? 'Đang xuất…' : 'Xuất video' }}</button>
    </div>

    <div class="mid">
      <!-- preview -->
      <div class="panel monitor-panel">
        <div class="monitor" :style="{ aspectRatio: aspect.w + ' / ' + aspect.h }">
          <template v-if="cur">
            <video v-if="cur.mediaFile && isVideo(cur.mediaFile)" :src="media(cur)" muted preload="metadata" />
            <img v-else-if="cur.mediaFile" :src="media(cur)" />
            <div v-else class="noimg muted">Cảnh chưa có hình</div>
            <img v-if="overlayUrl(cur, curIdx)" class="ov" :src="overlayUrl(cur, curIdx)" />
          </template>
        </div>
        <div class="row controls">
          <button class="primary" @click="toggle"><Icon :name="playing ? 'pause' : 'play'" />{{ playing ? 'Dừng' : 'Phát' }}</button>
          <span class="time">{{ fmt(t) }} / {{ fmt(total) }}</span>
          <span class="grow" />
          <label class="row zoomlab" title="Phóng to timeline"><Icon name="zoom" /> <input aria-label="Phóng to timeline" v-model.number="zoom" type="range" min="15" max="160" step="5" /></label>
        </div>
        <audio ref="audioEl" class="hid" />
        <audio v-if="musicUrl" ref="musicEl" class="hid" :src="musicUrl" loop @loadedmetadata="(e) => (musicLen = e.target.duration)" />
        <div v-if="project.scriptMode" class="muted small">Chữ hiện đúng vị trí và màu như khi xuất video.</div>
        <div v-else class="muted small">Ở chế độ giọng đọc, chữ không in lên hình (phụ đề lưu ở file .srt).</div>
      </div>

      <!-- inspector -->
      <div class="panel inspector">
        <template v-if="selected">
          <div class="row">
            <h3 class="grow" style="margin:0">Cảnh {{ (selItem?.i ?? 0) + 1 }} · {{ (selItem?.d ?? 0).toFixed(1) }}s</h3>
            <button class="small" title="Dời sang trái" @click="move(selected, -1)">◀</button>
            <button class="small" title="Dời sang phải" @click="move(selected, 1)">▶</button>
            <button class="small" @click="duplicate(selected)">⧉ Nhân đôi</button>
            <button class="small danger icon-only" title="Xoá cảnh" aria-label="Xoá cảnh" @click="remove(selected)"><Icon name="x" /></button>
          </div>

          <div class="field" style="margin-top:10px">
            <label>{{ project.scriptMode ? 'Chữ trên hình (script)' : 'Lời thoại' }}</label>
            <textarea v-model="selected.narration" rows="4" @input="changed" />
            <div class="muted small">{{ words(selected) }} từ<span v-if="project.scriptMode"> · tự động {{ autoDur(selected).toFixed(1) }}s (3 từ/giây)</span></div>
          </div>

          <div class="field">
            <label>Độ dài cảnh (giây){{ selected.customDuration ? ' — đặt tay' : ' — tự động' }}</label>
            <div class="row">
              <input type="number" min="0.5" step="0.1" style="width:100px" :value="selItem ? selItem.d.toFixed(1) : ''"
                     @change="customSet(selected, $event.target.value)" />
              <button class="small" :disabled="!selected.customDuration" @click="customSet(selected, 0)">Tự động</button>
              <span class="muted small">hoặc kéo mép phải của cảnh trên timeline</span>
            </div>
          </div>

          <div class="field">
            <label>Hình</label>
            <div class="row">
              <button class="small" @click="replaceMedia(selected)">⬆ Thay hình</button>
              <button v-if="selected.mediaFile" class="small" @click="clearKind(selected, 'media')">Bỏ hình</button>
              <span v-if="!selected.mediaFile" class="muted small">Chưa có hình (tạo bằng Flow/Canva ở trang dự án)</span>
            </div>
          </div>

          <div v-if="!project.scriptMode" class="field">
            <label>Giọng đọc</label>
            <div class="row">
              <button class="small" :disabled="busyVoice.has(selected.id) || !selected.narration" @click="makeVoice(selected)">
                <Icon name="mic" /> {{ busyVoice.has(selected.id) ? 'Đang tạo…' : selected.audioFile ? 'Tạo lại giọng' : 'Tạo giọng' }}</button>
              <button class="small" @click="uploadAudio(selected)">⬆ Audio</button>
              <button v-if="selected.audioFile" class="small" @click="clearKind(selected, 'audio')">Bỏ audio</button>
            </div>
            <audio v-if="audio(selected)" :src="audio(selected)" controls class="aud" />
          </div>
        </template>
        <div v-else class="muted">Bấm một cảnh trên timeline để sửa.</div>
        <button class="small" style="margin-top:8px" @click="addScene(selItem ? selItem.i : null)">＋ Thêm cảnh sau cảnh này</button>
      </div>
    </div>

    <!-- timeline -->
    <div class="panel tl-panel">
      <div class="tl">
        <div class="labels">
          <div class="lab ruler-lab" />
          <div class="lab">Hình</div>
          <div class="lab">{{ project.scriptMode ? 'Chữ' : 'Phụ đề' }}</div>
          <div class="lab">Giọng</div>
          <div class="lab">Nhạc</div>
        </div>
        <div class="scroll">
          <div class="canvas" :style="{ width: width + 'px' }">
            <div class="ruler" @click="onRulerClick">
              <span v-for="s in ticks" :key="s" class="tick" :style="{ left: s * zoom + 'px' }">{{ s % 5 === 0 || zoom >= 40 ? s : '' }}</span>
            </div>

            <div class="track">
              <div v-for="it in items" :key="it.s.id" class="blk vid" :class="{ on: it.s.id === sel, drag: dragId === it.s.id }"
                   :style="{ left: it.start * zoom + 'px', width: Math.max(8, it.d * zoom - 2) + 'px',
                             transform: dragId === it.s.id ? `translateX(${dragDx}px)` : '',
                             backgroundImage: it.s.mediaFile && !isVideo(it.s.mediaFile) ? `url(${media(it.s)})` : '' }"
                   @pointerdown="startDrag($event, it)">
                <span class="idx">{{ it.i + 1 }}</span>
                <span v-if="it.s.mediaFile && isVideo(it.s.mediaFile)" class="ico"><Icon name="film" /></span>
                <span v-if="!it.s.mediaFile" class="ico muted">Chưa có hình</span>
                <span class="dd">{{ it.d.toFixed(1) }}s</span>
                <i class="handle" title="Kéo để đổi độ dài" @pointerdown="startResize($event, it)" />
              </div>
            </div>

            <div class="track">
              <div v-for="it in items" :key="it.s.id" class="blk txt" :class="{ on: it.s.id === sel }"
                   :style="{ left: it.start * zoom + 'px', width: Math.max(8, it.d * zoom - 2) + 'px' }"
                   @click="selectAndSeek(it)">
                <span>{{ it.s.narration || '(trống)' }}</span>
              </div>
            </div>

            <div class="track">
              <template v-if="!project.scriptMode">
                <div v-for="it in items" :key="it.s.id" class="blk aud" :class="{ none: !it.s.audioFile, on: it.s.id === sel }"
                     :style="{ left: it.start * zoom + 'px', width: Math.max(8, (it.s.audioFile ? Math.min(it.d, (it.s.audioDuration || it.d) + 0.3) : it.d) * zoom - 2) + 'px' }"
                     @click="selectAndSeek(it)">
                  <span><Icon v-if="it.s.audioFile" name="mic" :size="12" /> {{ it.s.audioFile ? (it.s.audioDuration || 0).toFixed(1) + 's' : 'chưa có giọng' }}</span>
                </div>
              </template>
              <div v-else class="blk off" :style="{ left: 0, width: total * zoom + 'px' }"><span>Chế độ script: không dùng giọng</span></div>
            </div>

            <div class="track">
              <div v-if="project.musicFile" class="blk mus" :style="{ left: 0, width: total * zoom + 'px' }">
                <span><Icon name="music" /> {{ project.musicFile }} · từ giây {{ project.musicStart || 0 }} · {{ Math.round((project.musicVolume ?? 0.25) * 100) }}%</span>
              </div>
              <div v-else class="blk off" :style="{ left: 0, width: Math.max(300, total * zoom) + 'px' }"><span>Chưa có nhạc nền (thêm ở trang dự án)</span></div>
            </div>

            <div class="playhead" :style="{ left: t * zoom + 'px' }" />
          </div>
        </div>
      </div>
      <div class="muted small" style="margin-top:6px">
        Bấm cảnh để chọn · kéo cảnh sang trái/phải để đổi thứ tự · kéo mép phải để đổi độ dài · bấm thước giờ để tua · phím cách để phát/dừng.
      </div>
    </div>

  </div>
</template>

<style scoped>
.editor { min-width: 0; display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.top { gap: 8px; }
.name { font-size: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.small { font-size: 12px; }
.mid { display: grid; grid-template-columns: minmax(260px, 340px) 1fr; gap: 12px; align-items: start; }
.monitor-panel { display: flex; flex-direction: column; gap: 8px; }
.monitor { position: relative; width: 100%; max-height: 52vh; margin: 0 auto; background: #000; border-radius: 8px; overflow: hidden; }
.monitor img, .monitor video { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.monitor .ov { pointer-events: none; }
.noimg { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 13px; }
.controls { gap: 8px; }
.time { font-variant-numeric: tabular-nums; font-size: 13px; }
.zoomlab { gap: 4px; margin: 0; color: var(--muted); }
.zoomlab input { width: 110px; padding: 0; }
.hid { display: none; }
.aud { width: 100%; height: 32px; margin-top: 6px; }
.inspector textarea { min-height: 80px; }

.tl-panel { padding: 10px; }
.tl { display: flex; min-width: 0; }
.labels { width: 64px; flex-shrink: 0; }
.lab { height: 46px; display: flex; align-items: center; font-size: 12px; color: var(--muted); }
.lab.ruler-lab { height: 24px; }
.scroll { flex: 1; min-width: 0; overflow-x: auto; overflow-y: hidden; padding-bottom: 6px; }
.canvas { position: relative; }
.ruler { position: relative; height: 24px; border-bottom: 1px solid var(--border); cursor: pointer; }
.tick { position: absolute; top: 0; height: 24px; border-left: 1px solid var(--border); padding-left: 3px; font-size: 10px; color: var(--muted); user-select: none; }
.track { position: relative; height: 46px; }
.blk { position: absolute; top: 3px; height: 40px; border-radius: 6px; overflow: hidden; font-size: 12px; user-select: none;
       border: 1px solid var(--border); background: var(--panel-2); display: flex; align-items: center; padding: 0 6px; }
.blk span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.blk.vid { background-size: cover; background-position: center; color: #fff; cursor: grab; touch-action: none; }
.blk.vid .idx { position: absolute; left: 3px; top: 2px; background: rgba(0,0,0,.65); padding: 0 5px; border-radius: 4px; font-size: 11px; }
.blk.vid .dd { position: absolute; right: 8px; bottom: 2px; background: rgba(0,0,0,.65); padding: 0 4px; border-radius: 4px; font-size: 10px; }
.blk.vid .ico { margin: auto; }
.blk.vid.drag { opacity: .75; z-index: 5; cursor: grabbing; }
.blk.on { border-color: var(--accent); box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 55%, transparent); }
.handle { position: absolute; right: 0; top: 0; bottom: 0; width: 9px; cursor: ew-resize; background: rgba(124,92,255,.6); }
.blk.txt { background: color-mix(in srgb, var(--accent-2) 16%, var(--panel-2)); cursor: pointer; }
.blk.aud { background: color-mix(in srgb, var(--ok) 22%, var(--panel-2)); cursor: pointer; }
.blk.aud.none { background: repeating-linear-gradient(45deg, var(--panel-2), var(--panel-2) 6px, var(--bg) 6px, var(--bg) 12px); color: var(--muted); }
.blk.mus { background: color-mix(in srgb, var(--warn) 24%, var(--panel-2)); }
.blk.off { background: var(--bg); color: var(--muted); border-style: dashed; }
.playhead { position: absolute; top: 0; bottom: 0; width: 2px; background: #ff4d6d; pointer-events: none; z-index: 8; }

@media (max-width: 820px) {
  .mid { grid-template-columns: 1fr; }
  .monitor { max-height: 46vh; }
  .labels { width: 46px; }
}
</style>
