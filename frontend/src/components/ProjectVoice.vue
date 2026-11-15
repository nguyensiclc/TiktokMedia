<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import VoicePicker from './VoicePicker.vue'

/** Per-project narration voice + tempo. null on the project = follow the Settings default. */
const props = defineProps({ project: Object })
const emit = defineEmits(['changed'])

const settings = ref(null)
const profiles = ref([])
const profileError = ref('')
const open = ref(false)

const vieneu = computed(() => settings.value?.voiceProvider !== 'voicebox')
const defaultVoice = computed(() => (vieneu.value ? settings.value?.vieneuVoice : settings.value?.voiceProfileId) || '')
const defaultSpeed = computed(() => settings.value?.voiceSpeed || 1)

const voice = computed(() => (props.project.voice != null ? props.project.voice : defaultVoice.value))
const speed = computed(() => (props.project.voiceSpeed != null ? props.project.voiceSpeed : defaultSpeed.value))
const custom = computed(() => props.project.voice != null || props.project.voiceSpeed != null)

const voiceName = computed(() => {
  const id = voice.value
  if (!id) return vieneu.value ? 'Hải Đăng (mặc định)' : 'chưa chọn'
  return profiles.value.find((p) => p.id === id)?.name || id
})

async function loadProfiles() {
  try {
    profiles.value = await api.voiceProfiles()
    profileError.value = ''
  } catch (e) {
    profiles.value = []
    profileError.value = e.message
  }
}

const starting = ref(false)

/** Starts the VieNeu server and reloads the voice list when the start job finishes. */
async function startVieneu() {
  starting.value = true
  try {
    const job = await api.post('/api/voice/vieneu/start')
    const es = new EventSource(`/api/jobs/${job.id}/stream`)
    const done = async () => { es.close(); starting.value = false; await loadProfiles() }
    es.addEventListener('status', done)
    es.onerror = done
  } catch (e) {
    profileError.value = e.message
    starting.value = false
  }
}

function setVoice(v) {
  props.project.voice = v
  emit('changed')
}

function setSpeed(e) {
  props.project.voiceSpeed = Number(e.target.value)
  emit('changed')
}

function useDefaults() {
  props.project.voice = null
  props.project.voiceSpeed = null
  emit('changed')
}

onMounted(async () => {
  try { settings.value = await api.settings() } catch { settings.value = {} }
  loadProfiles()
})
</script>

<template>
  <div class="panel">
    <div class="row">
      <h3 class="grow" style="margin:0"><Icon name="mic" />Giọng đọc của dự án</h3>
      <button class="small" @click="open = !open">{{ open ? 'Thu gọn' : 'Đổi giọng / tốc độ' }}</button>
    </div>
    <div class="row summary">
      <span class="badge">{{ voiceName }}</span>
      <span class="badge">×{{ Number(speed).toFixed(2) }}</span>
      <span class="muted small">{{ custom ? 'Cài riêng cho dự án này' : 'Đang theo mặc định trong Cài đặt' }}</span>
      <span class="grow" />
      <button v-if="custom" class="small" @click="useDefaults">Dùng mặc định</button>
    </div>

    <template v-if="open">
      <div class="field" style="margin-top:10px">
        <label>
          Tốc độ đọc: <strong>×{{ Number(speed).toFixed(2) }}</strong>
          <span class="muted small">({{ speed < 0.97 ? 'chậm hơn, hợp đọc thơ' : speed > 1.03 ? 'nhanh hơn' : 'bình thường' }})</span>
        </label>
        <div class="row">
          <span class="muted small">0.5</span>
          <input class="grow" type="range" min="0.5" max="1.5" step="0.05" :value="speed" @input="setSpeed" />
          <span class="muted small">1.5</span>
        </div>
      </div>

      <div class="row" style="margin:6px 0">
        <label class="grow" style="margin:0">Giọng (bấm nút phát để nghe thử ở tốc độ trên, bấm dòng để chọn)</label>
        <button class="small" @click="loadProfiles">↻ Tải lại</button>
      </div>
      <div v-if="profileError" class="row muted small">
        <span class="grow">{{ vieneu ? 'VieNeu-TTS chưa chạy nên chưa có danh sách giọng.' : profileError }}</span>
        <button v-if="vieneu" class="small primary" :disabled="starting" @click="startVieneu">
          {{ starting ? 'Đang khởi động… (khoảng 20 giây)' : 'Khởi động VieNeu' }}
        </button>
      </div>
      <VoicePicker v-else :model-value="voice" :voices="profiles" :speed="speed"
                   :default-option="vieneu ? { id: '', name: 'Hải Đăng (mặc định của VieNeu)', gender: 'male' } : null"
                   @update:model-value="setVoice" />
      <p class="muted small" style="margin:6px 0 0">
        Đổi giọng hoặc tốc độ xong, bấm <strong>Tạo giọng tất cả</strong> để tạo lại audio cho các cảnh.
      </p>
    </template>
  </div>
</template>

<style scoped>
.summary { margin-top: 8px; gap: 8px; flex-wrap: wrap; }
.small { font-size: 12px; }
</style>
