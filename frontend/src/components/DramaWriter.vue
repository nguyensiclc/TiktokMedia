<script setup>
import { ref, computed, watch } from 'vue'

/**
 * Drama without a script: pick a genre → 3 ideas → pick one → series outline + episode 1 → later episodes.
 * Each finished screenplay is handed up with "use" so it fills the script box for "Chia shot".
 */
const props = defineProps({ project: Object, busy: Boolean })
const emit = defineEmits(['run', 'use'])

const GENRES = ['Đấu đá công sở', 'Mẹ chồng nàng dâu', 'Thân phận ẩn giấu', 'Bị phản bội rồi trả thù', 'Hào môn tranh gia sản', 'Học đường', 'Bí ẩn, trinh thám']

const d = computed(() => props.project.drama || null)
const hasSeries = computed(() => !!(d.value && d.value.episodes?.length))
const open = ref(!hasSeries.value)
const genre = ref(d.value?.genre || '')
const note = ref(d.value?.note || '')
const custom = ref('')

const nextEpisode = computed(() => (d.value?.episodes || []).find((e) => !(e.script || '').trim()))

function ideas() { emit('run', 'ideas', { genre: genre.value, note: note.value }) }
function pick(i) { emit('run', 'series', { index: i }) }
function fromLogline() { emit('run', 'series', { logline: custom.value }) }
function write(n) { emit('run', 'episode', { n }) }

// When Claude finishes an episode, put it in the script box right away.
const scripts = computed(() => (d.value?.episodes || []).map((e) => e.script || ''))
watch(scripts, (now, before) => {
  if (!before) return
  const i = now.findIndex((s, k) => s && s !== before[k])
  if (i >= 0) emit('use', d.value.episodes[i])
})
watch(hasSeries, (v) => { if (v) open.value = false })
</script>

<template>
  <div class="panel drama">
    <div class="row">
      <h3 class="grow" style="margin:0"><Icon name="sparkles" /> {{ hasSeries ? d.title : 'Chưa có kịch bản? Để Claude nghĩ ý tưởng' }}</h3>
      <button v-if="hasSeries" class="small" @click="open = !open">{{ open ? 'Thu gọn' : 'Ý tưởng khác' }}</button>
    </div>
    <p v-if="hasSeries" class="muted small logline">{{ d.logline }}</p>

    <!-- 1. Genre → ideas -->
    <template v-if="open || !hasSeries">
      <div class="chips" role="group" aria-label="Dòng drama">
        <button v-for="g in GENRES" :key="g" type="button" class="chip small" :class="{ on: genre === g }" @click="genre = genre === g ? '' : g">{{ g }}</button>
      </div>
      <div class="row" style="margin-top:8px">
        <input v-model="note" class="grow" placeholder="Thêm mong muốn (tuỳ chọn): bối cảnh Hà Nội, nữ chính 25 tuổi, có chút tình cảm…" />
        <button class="primary" :disabled="busy" @click="ideas"><Icon name="sparkles" /> Gợi ý 3 ý tưởng</button>
      </div>

      <div v-if="d?.ideas?.length" class="ideas">
        <div v-for="(it, i) in d.ideas" :key="i" class="idea">
          <strong>{{ it.title }}</strong>
          <p>{{ it.logline }}</p>
          <p class="muted small"><b>5 giây đầu:</b> {{ it.hook }}</p>
          <p class="muted small"><b>Vì sao giữ chân:</b> {{ it.why }}</p>
          <button class="small primary" :disabled="busy" @click="pick(i)">Chọn · viết dàn ý và tập 1</button>
        </div>
      </div>

      <details class="own">
        <summary class="small">Hoặc tự gõ một ý tưởng</summary>
        <div class="row" style="margin-top:6px">
          <input v-model="custom" class="grow" placeholder="VD: Cô dâu bị nhà chồng đuổi đi đúng ngày cưới, 3 năm sau quay lại làm chủ công ty của họ" />
          <button class="small" :disabled="busy || !custom.trim()" @click="fromLogline">Viết dàn ý</button>
        </div>
      </details>
      <p v-if="hasSeries" class="muted small">Chọn ý tưởng mới sẽ thay toàn bộ dàn ý và kịch bản các tập hiện có.</p>
    </template>

    <!-- 2. Outline: write / use each episode -->
    <div v-if="hasSeries" class="eps">
      <div v-for="e in d.episodes" :key="e.n" class="row ep">
        <span class="n">{{ e.n }}</span>
        <div class="grow txt">
          <div class="clamp" :title="e.events"><strong>{{ e.title }}</strong> <span class="muted small">{{ e.events }}</span></div>
          <div class="muted small clamp" :title="e.hook">Móc cuối: {{ e.hook }}</div>
        </div>
        <template v-if="(e.script || '').trim()">
          <button class="small primary" title="Điền kịch bản tập này vào ô bên dưới để chia shot" @click="emit('use', e)">Dùng tập {{ e.n }}</button>
          <button class="small" :disabled="busy" title="Viết lại kịch bản tập này" @click="write(e.n)">Viết lại</button>
        </template>
        <button v-else class="small" :disabled="busy" :class="{ primary: nextEpisode && nextEpisode.n === e.n }" @click="write(e.n)">Viết tập {{ e.n }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.drama { margin-bottom: var(--s3, 12px); }
.logline { margin: 4px 0 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.chip { border-radius: 999px; font-weight: 500; }
.chip.on { background: var(--accent); color: var(--on-accent); border-color: transparent; }
.ideas { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin-top: 12px; }
.idea { border: 1px solid var(--border); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 4px; }
.idea p { margin: 0; }
.idea button { align-self: flex-start; margin-top: auto; }
.own { margin-top: 10px; }
.eps { margin-top: 10px; display: flex; flex-direction: column; }
.ep { gap: 10px; padding: 6px 0; border-top: 1px solid var(--border); align-items: flex-start; flex-wrap: wrap; }
.n { width: 22px; text-align: right; font-weight: 600; color: var(--muted); }
.txt { min-width: 200px; }
.clamp { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.small { font-size: 12px; }
</style>
