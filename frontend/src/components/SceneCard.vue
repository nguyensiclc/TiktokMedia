<script setup>
import { computed } from 'vue'
import { fileUrl, isVideo } from '../api'

const props = defineProps({
  scene: Object,
  index: Number,
  total: Number,
  projectId: String,
  version: String,
  busy: Boolean,
  isRef: Boolean,
  scriptMode: Boolean,
  /** Drama: cast names a line can be given to; null = not a drama (no speaker field). */
  cast: Array
})
const screenText = computed(() => (props.scene.speaker || '').trim().toUpperCase() === 'TEXT')
function setSpeaker(v) {
  props.scene.speaker = v || null
  emit('change')
}
const emit = defineEmits(['change', 'remove', 'move', 'flow', 'gemini', 'canva', 'voice', 'upload', 'clear', 'ref'])

const mediaUrl = computed(() => props.scene.mediaFile && fileUrl(props.projectId, 'assets', props.scene.mediaFile, props.version))
const audioUrl = computed(() => props.scene.audioFile && fileUrl(props.projectId, 'assets', props.scene.audioFile, props.version))

function pick(kind, accept) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = accept
  input.onchange = () => input.files[0] && emit('upload', kind, input.files[0])
  input.click()
}

const words = computed(() => (props.scene.narration || '').trim().split(/\s+/).filter(Boolean).length)
const shown = computed(() => (words.value ? Math.max(2, words.value / 3) : props.scene.duration || 4))
const isLast = computed(() => props.index === props.total - 1)

const sourceLabel = { gemini: 'Gemini', flow: 'Google Flow', canva: 'Canva', upload: 'Tải lên' }
</script>

<template>
  <div class="panel scene" :class="{ busy }">
    <div class="media">
      <video v-if="mediaUrl && isVideo(scene.mediaFile)" :src="mediaUrl" controls muted />
      <img v-else-if="mediaUrl" :src="mediaUrl" />
      <div v-else class="placeholder muted">Chưa có hình</div>
      <span v-if="scene.mediaSource" class="badge src">{{ sourceLabel[scene.mediaSource] || scene.mediaSource }}</span>
      <span v-if="isRef" class="badge ref" title="Các cảnh Canva khác dùng ảnh này để giữ nhân vật và phong cách"><Icon name="star" :size="13" /> Tham chiếu</span>
    </div>

    <div class="body">
      <div class="row head">
        <strong>Cảnh {{ index + 1 }}</strong>
        <span v-if="busy" class="badge">⏳ đang xử lý</span>
        <span class="grow" />
        <button class="small" :disabled="index === 0" @click="emit('move', -1)">↑</button>
        <button class="small" :disabled="index === total - 1" @click="emit('move', 1)">↓</button>
        <button class="small danger icon-only" title="Xoá cảnh" aria-label="Xoá cảnh" @click="emit('remove')"><Icon name="x" /></button>
      </div>

      <div v-if="cast" class="field row speaker">
        <label :for="`spk-${scene.id}`" style="margin:0">Người nói</label>
        <select :id="`spk-${scene.id}`" :value="scene.speaker || ''" @change="setSpeaker($event.target.value)">
          <option value="">— Không lời / người dẫn —</option>
          <option value="TEXT">Chữ trên màn hình (không đọc)</option>
          <option v-for="n in cast" :key="n" :value="n">{{ n }}</option>
          <option v-if="scene.speaker && !screenText && !cast.includes(scene.speaker)" :value="scene.speaker">{{ scene.speaker }}</option>
        </select>
      </div>
      <div class="field">
        <label>{{ scriptMode ? 'Lời script (hiện trên hình cho người đọc)' : screenText ? 'Chữ hiện trên màn hình (không đọc)' : 'Lời thoại (giọng đọc)' }}</label>
        <textarea v-model="scene.narration" rows="2" @input="emit('change')" />
      </div>
      <div class="field">
        <label>Prompt hình ảnh (Flow / Canva)</label>
        <textarea v-model="scene.imagePrompt" rows="2" @input="emit('change')" />
      </div>

      <div class="row actions">
        <button class="small" :disabled="busy" title="Tạo ảnh bằng Gemini API (nhanh, vài giây, có phí)" @click="emit('gemini')"><Icon name="zap" /> Gemini</button>
        <button class="small" :disabled="busy" title="Tạo lại ảnh cảnh này bằng Google Flow" @click="emit('flow', 'image')"><Icon name="flow" /> Flow ảnh</button>
        <button class="small" :disabled="busy" @click="emit('flow', 'video')"><Icon name="film" /> Flow video</button>
        <button class="small" :disabled="busy" @click="emit('canva')"><Icon name="palette" /> Canva</button>
        <button class="small" @click="pick('media', 'image/*,video/*')">⬆ Hình/clip</button>
        <button v-if="scene.mediaFile" class="small" @click="emit('clear', 'media')">Bỏ hình</button>
        <button v-if="scene.canvaMediaId && !isRef" class="small" @click="emit('ref')"><Icon name="star" /> Làm tham chiếu</button>
      </div>

      <div v-if="scriptMode" class="row actions">
        <span class="muted">⏱ {{ words }} từ → {{ shown.toFixed(1) }}s (3 từ mỗi giây, tối thiểu 2s)</span>
        <span v-if="!isLast && words" class="muted">· chữ kết thúc bằng "..."</span>
      </div>
      <div v-else class="row actions">
        <button v-if="!screenText" class="small" :disabled="busy || !scene.narration" @click="emit('voice')"><Icon name="mic" /> Tạo giọng</button>
        <button class="small" @click="pick('audio', 'audio/*')">⬆ Audio</button>
        <audio v-if="audioUrl" :src="audioUrl" controls class="grow" />
        <span v-if="scene.audioDuration" class="muted">{{ scene.audioDuration.toFixed(1) }}s</span>
        <button v-if="scene.audioFile" class="small" @click="emit('clear', 'audio')">Bỏ audio</button>
        <template v-if="!scene.audioFile">
          <span class="muted">Thời lượng</span>
          <input v-model.number="scene.duration" type="number" min="1" step="0.5" class="dur" @input="emit('change')" />
          <span class="muted">s</span>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scene { display: flex; gap: 16px; }
.scene.busy { border-color: var(--warn); }
.media {
  width: 240px; flex-shrink: 0; aspect-ratio: 16/9; background: var(--bg); border-radius: 8px;
  overflow: hidden; position: relative; display: flex; align-items: center; justify-content: center;
}
.media img, .media video { width: 100%; height: 100%; object-fit: contain; }
.placeholder { font-size: 12px; }
.src { position: absolute; left: 6px; top: 6px; }
.ref { position: absolute; right: 6px; top: 6px; background: var(--warn); color: #1a1300; border-color: var(--warn); }
.scene:has(.ref) { border-color: var(--warn); }
.body { flex: 1; min-width: 0; }
.head { margin-bottom: 8px; }
.actions { margin-top: 6px; }
audio { height: 30px; min-width: 160px; }
.dur { width: 70px; }
.speaker { gap: 8px; align-items: center; }
.speaker select { width: auto; min-width: 180px; }
@media (max-width: 820px) {
  .scene { flex-direction: column; gap: 10px; }
  .media { width: 100%; aspect-ratio: auto; height: 46vh; max-height: 420px; }
  audio { min-width: 0; width: 100%; }
}
</style>
