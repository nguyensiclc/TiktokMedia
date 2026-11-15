<script setup>
import { ref, computed, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: String,
  voices: Array,
  /** Extra first row, e.g. { id: '', name: 'Mặc định (Hải Đăng)' } */
  defaultOption: Object,
  /** Reading tempo used for previews (1 = normal) */
  speed: { type: Number, default: 1 }
})
const emit = defineEmits(['update:modelValue'])

const sample = ref('')
const filter = ref('')
const playing = ref(null) // voice id currently playing
const loading = ref(null) // voice id being generated
const error = ref('')
const cached = ref(new Set()) // voices already generated for the current sample text
let audio = null
let audioUrl = null

const rows = computed(() => {
  const list = [...(props.defaultOption ? [props.defaultOption] : []), ...(props.voices || [])]
  const q = filter.value.trim().toLowerCase()
  return q ? list.filter((v) => `${v.name} ${v.gender || ''} ${v.description || ''}`.toLowerCase().includes(q)) : list
})

function stop() {
  if (audio) {
    audio.pause()
    audio = null
  }
  if (audioUrl) URL.revokeObjectURL(audioUrl)
  audioUrl = null
  playing.value = null
}

async function play(v) {
  if (playing.value === v.id) return stop()
  stop()
  error.value = ''
  loading.value = v.id
  try {
    const q = new URLSearchParams({ voice: v.id || '' })
    if (sample.value.trim()) q.set('text', sample.value.trim())
    q.set('speed', String(props.speed || 1))
    const res = await fetch(`/api/voice/preview?${q}`)
    if (!res.ok) {
      const body = await res.json().catch(() => ({}))
      throw new Error(body.error || `HTTP ${res.status}`)
    }
    audioUrl = URL.createObjectURL(await res.blob())
    cached.value = new Set([...cached.value, v.id])
    audio = new Audio(audioUrl)
    audio.onended = stop
    playing.value = v.id
    await audio.play()
  } catch (e) {
    error.value = `${v.name}: ${e.message}`
    stop()
  } finally {
    loading.value = null
  }
}

function sampleChanged() {
  cached.value = new Set()
  stop()
}

onBeforeUnmount(stop)
</script>

<template>
  <div class="picker">
    <div class="row top">
      <input v-model="sample" class="grow" placeholder="Câu đọc thử (để trống = câu mẫu)" @change="sampleChanged" />
      <input v-model="filter" style="width:160px" placeholder="Lọc: nam, nữ, tên…" />
    </div>
    <div v-if="error" class="err small">{{ error }}</div>
    <div class="list">
      <div v-for="v in rows" :key="v.id" class="voice" :class="{ selected: modelValue === v.id }"
           @click="emit('update:modelValue', v.id)">
        <button class="play" :class="{ on: playing === v.id }" :disabled="loading && loading !== v.id"
                :title="playing === v.id ? 'Dừng' : 'Nghe thử'" @click.stop="play(v)">
          <span v-if="loading === v.id" class="spin" />
          <Icon v-else :name="playing === v.id ? 'stop' : 'play'" />
        </button>
        <div class="grow">
          <div class="name">{{ v.name }}</div>
          <div class="muted small">
            {{ [v.gender === 'male' ? 'Nam' : v.gender === 'female' ? 'Nữ' : v.gender, v.language, v.description].filter(Boolean).join(' · ') }}
            <span v-if="cached.has(v.id)" title="Đã có sẵn, phát ngay" aria-label="Đã có sẵn"><Icon name="zap" /></span>
          </div>
        </div>
        <span v-if="modelValue === v.id" class="badge chosen"><Icon name="check" /> Đang dùng</span>
      </div>
      <div v-if="!rows.length" class="muted small" style="padding:10px">Không có giọng nào.</div>
    </div>
    <div class="muted small">
      Bấm nút phát để nghe thử. Lần đầu mỗi giọng mất vài giây để tạo audio, những lần sau phát ngay. Bấm vào một dòng để chọn giọng đó.
    </div>
  </div>
</template>

<style scoped>
.picker { display: flex; flex-direction: column; gap: 8px; }
.list { max-height: 340px; overflow: auto; border: 1px solid var(--border); border-radius: 8px; }
.voice { display: flex; align-items: center; gap: 10px; padding: 6px 10px; border-bottom: 1px solid var(--border); cursor: pointer; }
.voice:last-child { border-bottom: 0; }
.voice:hover { background: var(--panel-2); }
.voice.selected { background: color-mix(in srgb, var(--accent) 18%, transparent); }
.name { font-weight: 600; }
.play { width: 34px; height: 34px; border-radius: 50%; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.play.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.chosen { background: var(--accent); border-color: var(--accent); color: #fff; }
.spin { width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: s 0.8s linear infinite; }
@keyframes s { to { transform: rotate(360deg); } }
.small { font-size: 12px; }
</style>
