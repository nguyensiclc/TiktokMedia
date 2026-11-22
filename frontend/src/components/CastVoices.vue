<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../api'

/** Drama: one voice per speaker. A blank voice is picked automatically (by gender) on the next voice run. */
const props = defineProps({ project: Object })
const emit = defineEmits(['changed'])

const profiles = ref([])
const error = ref('')
const playing = ref('')
let audio = null

const GENDER = { male: 'Nam', female: 'Nữ' }

const lineCount = computed(() => {
  const n = {}
  for (const s of props.project.scenes || []) {
    const k = (s.speaker || '').trim().toLowerCase()
    if (k && k !== 'text' && (s.narration || '').trim()) n[k] = (n[k] || 0) + 1
  }
  return n
})
const rows = computed(() => [...(props.project.cast || [])].sort((a, b) =>
  (lineCount.value[b.name.trim().toLowerCase()] || 0) - (lineCount.value[a.name.trim().toLowerCase()] || 0)))

const fitting = (m) => {
  const all = profiles.value
  if (!m.gender) return all
  const g = (v) => { const x = (v.gender || '').toLowerCase(); return x.startsWith('f') || x.startsWith('nữ') ? 'female' : x.startsWith('m') || x.startsWith('nam') ? 'male' : '' }
  return [...all.filter((v) => g(v) === m.gender || !g(v)), ...all.filter((v) => g(v) && g(v) !== m.gender)]
}

function setVoice(m, v) {
  m.voice = v || null
  emit('changed')
}

function firstLine(m) {
  const k = m.name.trim().toLowerCase()
  return (props.project.scenes || []).find((s) => (s.speaker || '').trim().toLowerCase() === k && (s.narration || '').trim())?.narration
}

function stop() {
  if (audio) { audio.pause(); URL.revokeObjectURL(audio.src); audio = null }
  playing.value = ''
}

async function play(m) {
  if (playing.value === m.name) return stop()
  stop()
  error.value = ''
  playing.value = m.name
  try {
    const q = new URLSearchParams({ voice: m.voice || '' })
    const t = firstLine(m)
    if (t) q.set('text', t)
    const res = await fetch(`/api/voice/preview?${q}`)
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `Lỗi ${res.status}`)
    audio = new Audio(URL.createObjectURL(await res.blob()))
    audio.onended = stop
    await audio.play()
  } catch (e) {
    error.value = e.message
    playing.value = ''
  }
}

onMounted(async () => {
  try { profiles.value = await api.voiceProfiles() } catch (e) { error.value = 'Chưa lấy được danh sách giọng: ' + e.message }
})
onBeforeUnmount(stop)
</script>

<template>
  <div class="panel">
    <h3 style="margin:0 0 4px"><Icon name="mic" />Giọng từng nhân vật</h3>
    <p class="muted small" style="margin:0 0 10px">
      Mỗi vai đọc bằng giọng riêng. Vai để <strong>Tự chọn</strong> sẽ được gán một giọng cùng giới tính, chưa vai nào dùng,
      khi bấm <strong>Tạo giọng tất cả</strong>. Đổi giọng một vai xong, tạo giọng lại để áp dụng.
    </p>
    <div v-for="m in rows" :key="m.name" class="row cast">
      <div class="who">
        <strong>{{ m.name }}</strong>
        <span class="muted small">{{ [GENDER[m.gender], m.note].filter(Boolean).join(' · ') }}</span>
      </div>
      <span class="badge">{{ lineCount[m.name.trim().toLowerCase()] || 0 }} câu</span>
      <select :value="m.voice || ''" :aria-label="`Giọng của ${m.name}`" @change="setVoice(m, $event.target.value)">
        <option value="">Tự chọn</option>
        <option v-for="v in fitting(m)" :key="v.id" :value="v.id">{{ v.name }}{{ v.gender ? ` (${v.gender})` : '' }}</option>
      </select>
      <button class="small icon-only" :aria-label="`Nghe thử giọng ${m.name}`" :title="m.voice ? 'Nghe thử câu thoại đầu của vai' : 'Chọn giọng để nghe thử'"
              :disabled="!m.voice" @click="play(m)">{{ playing === m.name ? '■' : '▶' }}</button>
    </div>
    <p v-if="error" class="muted small" style="margin:6px 0 0">{{ error }}</p>
  </div>
</template>

<style scoped>
.cast { gap: 8px; padding: 6px 0; border-top: 1px solid var(--border); flex-wrap: wrap; }
.who { flex: 1; min-width: 160px; display: flex; flex-direction: column; }
select { width: 200px; max-width: 100%; }
.small { font-size: 12px; }
</style>
