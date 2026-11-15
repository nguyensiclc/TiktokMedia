<script setup>
import { ref, computed } from 'vue'
import { api, fileUrl } from '../api'

/** Narration mode (voice vs on-screen script) and the background music of one project. */
const props = defineProps({ project: Object, version: String })
const emit = defineEmits(['changed', 'error', 'music'])

const busy = ref(false)
const musicUrl = computed(() => props.project.musicFile && fileUrl(props.project.id, 'assets', props.project.musicFile, props.version))
const volume = computed(() => props.project.musicVolume ?? 0.25)

function setMode(script) {
  props.project.scriptMode = script
  emit('changed')
}

function setVolume(e) {
  props.project.musicVolume = Number(e.target.value)
  emit('changed')
}

const player = ref(null)
const start = computed(() => props.project.musicStart || 0)

function setStart(v) {
  const n = Math.max(0, Number(v) || 0)
  props.project.musicStart = Math.round(n * 10) / 10
  emit('changed')
}

// Take the start point from where the preview player currently is.
function startHere() {
  if (player.value) setStart(player.value.currentTime)
}

function previewFromStart() {
  const a = player.value
  if (!a) return
  a.currentTime = start.value
  a.play()
}

const mmss = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`

function pick() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac'
  input.onchange = async () => {
    if (!input.files[0]) return
    busy.value = true
    try {
      emit('music', await api.setMusic(props.project.id, input.files[0]))
    } catch (e) {
      emit('error', e.message)
    } finally {
      busy.value = false
    }
  }
  input.click()
}

async function remove() {
  busy.value = true
  try {
    emit('music', await api.deleteMusic(props.project.id))
  } catch (e) {
    emit('error', e.message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="panel">
    <h3 style="margin:0 0 8px"><Icon name="sliders" />Lời & nhạc nền</h3>

    <div class="modes">
      <label class="mode" :class="{ on: !project.scriptMode }">
        <input type="radio" :checked="!project.scriptMode" @change="setMode(false)" />
        <span><strong class="ih"><Icon name="mic" /> Giọng đọc</strong><br /><span class="muted small">Tạo audio từ lời thoại, phụ đề .srt kèm theo</span></span>
      </label>
      <label class="mode" :class="{ on: project.scriptMode }">
        <input type="radio" :checked="project.scriptMode" @change="setMode(true)" />
        <span><strong class="ih"><Icon name="file" /> Script cho người đọc</strong><br />
          <span class="muted small">Không tạo audio. Lời hiện ngay trên hình, ở vùng ít chi tiết nhất; mỗi cảnh hiện 3 từ mỗi giây
            và kết thúc bằng "..." (trừ cảnh cuối) để người đọc biết còn tiếp.</span></span>
      </label>
    </div>

    <div class="field" style="margin-top:12px">
      <label>Nhạc nền (không lời, phát suốt video, tự lặp và nhỏ dần ở cuối)</label>
      <div class="row">
        <button class="small" :disabled="busy" @click="pick">{{ project.musicFile ? '⬆ Đổi nhạc' : '⬆ Chọn file nhạc' }}</button>
        <audio v-if="musicUrl" ref="player" :src="musicUrl" controls class="grow" />
        <span v-else class="muted small grow">MP3, WAV, M4A, AAC, OGG, FLAC</span>
        <button v-if="project.musicFile" class="small danger" :disabled="busy" @click="remove">Bỏ nhạc</button>
      </div>
    </div>
    <div v-if="project.musicFile" class="field">
      <label>Bắt đầu nhạc từ giây thứ <strong>{{ start }}</strong> ({{ mmss(start) }})
        <span class="muted small">— phần trước đó bị cắt bỏ khi render</span></label>
      <div class="row">
        <input type="number" min="0" step="0.5" :value="start" style="width:90px" @change="setStart($event.target.value)" />
        <span class="muted small">giây</span>
        <button class="small" title="Tua trình phát tới vị trí mong muốn rồi bấm" @click="startHere"><Icon name="pin" /> Lấy vị trí đang phát</button>
        <button class="small" @click="previewFromStart"><Icon name="play" /> Nghe từ điểm bắt đầu</button>
      </div>
    </div>
    <div v-if="project.musicFile" class="field">
      <label>Âm lượng nhạc: <strong>{{ Math.round(volume * 100) }}%</strong>
        <span v-if="!project.scriptMode" class="muted small">(tự nhỏ lại khi có giọng đọc)</span></label>
      <input type="range" min="0" max="1" step="0.05" :value="volume" @input="setVolume" />
    </div>
  </div>
</template>

<style scoped>
.modes { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.mode { display: flex; gap: 8px; align-items: flex-start; padding: 10px; border: 1px solid var(--border);
        border-radius: 8px; cursor: pointer; margin: 0; color: var(--text); }
.mode.on { border-color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); }
.mode input { width: auto; margin-top: 3px; }
audio { height: 30px; min-width: 160px; }
.small { font-size: 12px; }
@media (max-width: 700px) { .modes { grid-template-columns: 1fr; } }
</style>
