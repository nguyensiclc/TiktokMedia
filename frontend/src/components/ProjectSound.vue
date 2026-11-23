<script setup>
import { ref, computed } from 'vue'
import { api, fileUrl } from '../api'

/** Background music of one project (the words are always shown on the pictures, no voice). */
const props = defineProps({ project: Object, version: String })
const emit = defineEmits(['changed', 'error', 'music'])

const busy = ref(false)
const musicUrl = computed(() => props.project.musicFile && fileUrl(props.project.id, 'assets', props.project.musicFile, props.version))
const volume = computed(() => props.project.musicVolume ?? 0.25)

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
    <h3 style="margin:0 0 8px"><Icon name="sliders" />Nhạc nền</h3>
    <p class="muted small" style="margin:0 0 8px">Lời script hiện ngay trên hình, mỗi cảnh hiện 3 từ mỗi giây và kết thúc bằng "..." (trừ cảnh cuối).</p>

    <div class="field">
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
      <label>Âm lượng nhạc: <strong>{{ Math.round(volume * 100) }}%</strong></label>
      <input type="range" min="0" max="1" step="0.05" :value="volume" @input="setVolume" />
    </div>
  </div>
</template>

<style scoped>
audio { height: 30px; min-width: 160px; }
.small { font-size: 12px; }
</style>
