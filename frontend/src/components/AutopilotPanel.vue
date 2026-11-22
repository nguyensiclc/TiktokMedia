<script setup>
import { ref, computed } from 'vue'
import { api } from '../api'

/** Adds topics to the posts list: one new project per topic, posted at the chosen times by the background queue. */
const emit = defineEmits(['added', 'error'])
const topics = ref('')
const niche = ref('history')
const scenes = ref(34)
const privacy = ref('SELF_ONLY')
const aiLabel = ref(true)
const chibi = ref(false)
const draft = ref(true)
const schedule = ref(true)
const startAt = ref(defaultStart())
const interval = ref(24)
const busy = ref(false)

const NICHES = [
  { id: 'history', label: 'Lịch sử · chiến tranh', scenes: 34 },
  { id: 'story', label: 'Truyện · cổ tích', scenes: 12 },
  { id: 'facts', label: 'Bạn có biết?', scenes: 7 },
]
const VIEW = [
  { id: 'SELF_ONLY', label: 'Chỉ mình tôi' },
  { id: 'FRIENDS', label: 'Bạn bè' },
  { id: 'PUBLIC', label: 'Mọi người' },
]
const INTERVALS = [
  { h: 6, label: '6 giờ' }, { h: 12, label: '12 giờ' }, { h: 24, label: '1 ngày' }, { h: 48, label: '2 ngày' }, { h: 168, label: '1 tuần' },
]
const list = computed(() => topics.value.split('\n').map((t) => t.trim()).filter(Boolean))

/** Tomorrow 19:00 local, as the value of an <input type="datetime-local">. */
function defaultStart() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  d.setHours(19, 0, 0, 0)
  return toLocalInput(d)
}
function toLocalInput(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
const preview = computed(() => {
  if (draft.value || !schedule.value || !startAt.value) return []
  const t0 = new Date(startAt.value).getTime()
  return list.value.slice(0, 3).map((t, i) => new Date(t0 + i * interval.value * 3600e3))
})
const fmt = (d) => d.toLocaleString('vi-VN', { weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

function pickNiche(n) {
  niche.value = n.id
  scenes.value = n.scenes
}

async function add() {
  busy.value = true
  try {
    const created = await api.post('/api/queue', {
      topics: topics.value, niche: niche.value, scenes: scenes.value, privacy: privacy.value, aiLabel: aiLabel.value, chibi: chibi.value, draft: draft.value,
      startAt: !draft.value && schedule.value && startAt.value ? new Date(startAt.value).toISOString() : '',
      intervalHours: interval.value,
    })
    topics.value = ''
    emit('added', created)
  } catch (e) {
    emit('error', e.message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="panel add">
    <h3><Icon name="plus" /> Thêm bài vào danh sách đăng</h3>
    <p class="muted small">
      Mỗi dòng một chủ đề. App tự tạo dự án, viết kịch bản, tạo hình bằng Flow, xuất ảnh 3:4, viết caption, rồi đăng
      lên TikTok: lưu vào bản nháp ngay khi xong, hoặc đăng đúng giờ hẹn.
    </p>
    <textarea v-model="topics" rows="3" placeholder="VD:&#10;Ngô Quyền và trận Bạch Đằng năm 938&#10;Quang Trung đại phá quân Thanh năm 1789" />
    <div class="opts">
      <div class="seg" role="radiogroup" aria-label="Ngách nội dung">
        <label v-for="n in NICHES" :key="n.id" :class="{ on: niche === n.id }">
          <input type="radio" name="add-niche" :checked="niche === n.id" @change="pickNiche(n)" /> {{ n.label }}
        </label>
      </div>
      <label class="inl">Số cảnh <input v-model.number="scenes" type="number" min="1" :max="34" style="width:70px" /></label>
      <label class="inl" title="Nhân vật đầu to, mắt to, thân nhỏ dễ thương (2–3 đầu)"><input v-model="chibi" type="checkbox" /> Nhân vật chibi dễ thương</label>
    </div>
    <div class="opts">
      <span class="muted small">Khi xong</span>
      <div class="seg" role="radiogroup" aria-label="Khi chuẩn bị xong">
        <label :class="{ on: draft }"><input v-model="draft" type="radio" name="add-mode" :value="true" /> Lưu nháp TikTok</label>
        <label :class="{ on: !draft }"><input v-model="draft" type="radio" name="add-mode" :value="false" /> Đăng lên TikTok</label>
      </div>
      <span v-if="draft" class="muted small">Không đăng gì: bài nằm trong Bản nháp của TikTok Studio, bạn tự đăng sau.</span>
    </div>
    <div v-if="!draft" class="opts">
      <label class="row chk"><input v-model="schedule" type="checkbox" /> Hẹn giờ đăng</label>
      <template v-if="schedule">
        <label class="inl">Bài đầu <input v-model="startAt" type="datetime-local" /></label>
        <label class="inl">mỗi bài cách
          <select v-model.number="interval"><option v-for="o in INTERVALS" :key="o.h" :value="o.h">{{ o.label }}</option></select>
        </label>
      </template>
      <span v-else class="muted small">Chỉ chuẩn bị sẵn, bấm "Đăng ngay" trong danh sách khi muốn đăng.</span>
    </div>
    <div class="opts">
      <span class="muted small">Ai được xem</span>
      <div class="seg" role="radiogroup" aria-label="Ai được xem">
        <label v-for="v in VIEW" :key="v.id" :class="{ on: privacy === v.id }">
          <input v-model="privacy" type="radio" name="add-view" :value="v.id" /> {{ v.label }}
        </label>
      </div>
      <label class="row chk"><input v-model="aiLabel" type="checkbox" /> Nhãn "Nội dung do AI tạo"</label>
    </div>
    <div class="row">
      <span class="muted small grow">
        {{ list.length }} chủ đề<template v-if="preview.length">: {{ preview.map(fmt).join(' · ') }}{{ list.length > 3 ? ' …' : '' }}</template>
      </span>
      <button class="primary" :disabled="!list.length || busy" @click="add"><Icon name="plus" /> Thêm vào danh sách</button>
    </div>
  </div>
</template>

<style scoped>
.add { display: grid; gap: 10px; }
.add h3 { margin: 0; display: flex; align-items: center; gap: 6px; }
.add p { margin: 0; }
.opts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.seg { display: flex; flex-wrap: wrap; gap: 2px; padding: 2px; border-radius: 8px; background: var(--fill); }
.seg label { display: inline-flex; align-items: center; padding: 4px 12px; border-radius: 6px; font-size: 13px; cursor: pointer; margin: 0; }
.seg label.on { background: var(--panel); box-shadow: 0 1px 2px rgba(0, 0, 0, .14), 0 0 0 .5px rgba(0, 0, 0, .06); font-weight: 600; }
.seg input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.seg label:has(input:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
.inl { display: flex; align-items: center; gap: 6px; font-size: 13px; margin: 0; }
.inl input, .inl select { width: auto; }
.chk { gap: 6px; font-size: 13px; margin: 0; }
.chk input { width: auto; }
</style>
