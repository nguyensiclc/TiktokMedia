<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { api, fileUrl } from '../api'

const props = defineProps({ project: Object })
const emit = defineEmits(['updated', 'error'])

// Images pasted/dropped/picked but not saved yet: [{ key, file, preview, name, description, saving }]
const pending = ref([])
const dragging = ref(false)
const nameInputs = ref({})
const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' }
let seq = 0

function nextName() {
  return `Nhân vật ${props.project.characters.length + pending.value.length + 1}`
}

function stage(files) {
  let first = null
  for (const file of files) {
    if (!EXT[file.type]) {
      emit('error', `Bỏ qua "${file.name}": chỉ nhận ảnh PNG, JPG hoặc WebP`)
      continue
    }
    const named = file.name && !file.name.startsWith('pasted-') && file.name !== 'image.png'
    const item = {
      key: ++seq,
      file,
      preview: URL.createObjectURL(file),
      name: named ? file.name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ') : nextName(),
      description: '',
      saving: false
    }
    pending.value.push(item)
    first ??= item
  }
  if (first) nextTick(() => nameInputs.value[first.key]?.select())
}

function pick() {
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = 'image/png,image/jpeg,image/webp'
  input.onchange = () => stage([...input.files])
  input.click()
}

// Ctrl+V anywhere on the project page. Every image on the clipboard becomes a pending character;
// plain text pastes (into the script fields etc.) are left alone.
function onPaste(e) {
  // The "start from images" panel takes page-wide pastes; this panel only takes them while hovered.
  if (!document.querySelector('.character-panel:hover')) return
  const blobs = [...(e.clipboardData?.items || [])]
    .filter((i) => i.kind === 'file' && i.type.startsWith('image/'))
    .map((i) => i.getAsFile())
    .filter(Boolean)
  if (!blobs.length) return
  e.preventDefault()
  stage(blobs.map((b, i) => {
    const type = EXT[b.type] ? b.type : 'image/png'
    const keepName = b.name && b.name !== 'image.png'
    return new File([b], keepName ? b.name : `pasted-${Date.now()}-${i}.${EXT[type]}`, { type })
  }))
}

function onDrop(e) {
  dragging.value = false
  stage([...(e.dataTransfer?.files || [])].filter((f) => f.type.startsWith('image/')))
}

async function save(item) {
  if (!item.name.trim()) return emit('error', 'Hãy đặt tên cho mọi nhân vật')
  item.saving = true
  try {
    const p = await api.addCharacter(props.project.id, item.file, item.name, item.description)
    discard(item)
    emit('updated', p)
    return true
  } catch (e) {
    emit('error', e.message)
    item.saving = false
    return false
  }
}

async function saveAll() {
  // Sequential: each save returns the whole project, so later responses include earlier ones.
  for (const item of [...pending.value]) {
    if (!(await save(item))) break
  }
}

function discard(item) {
  URL.revokeObjectURL(item.preview)
  pending.value = pending.value.filter((x) => x !== item)
  delete nameInputs.value[item.key]
}

async function update(c) {
  try {
    emit('updated', await api.updateCharacter(props.project.id, c.id, { name: c.name, description: c.description }))
  } catch (e) {
    emit('error', e.message)
  }
}

async function remove(c) {
  if (!confirm(`Xoá nhân vật "${c.name}"?`)) return
  try {
    emit('updated', await api.deleteCharacter(props.project.id, c.id))
  } catch (e) {
    emit('error', e.message)
  }
}

onMounted(() => window.addEventListener('paste', onPaste))
onBeforeUnmount(() => {
  window.removeEventListener('paste', onPaste)
  pending.value.forEach((p) => URL.revokeObjectURL(p.preview))
})
</script>

<template>
  <div class="panel character-panel" :class="{ dragging }"
       @dragover.prevent="dragging = true" @dragleave.self="dragging = false" @drop.prevent="onDrop">
    <div class="row">
      <h3 class="grow" style="margin:0">0 · Nhân vật tham chiếu ({{ project.characters.length }})</h3>
      <span class="muted small">trỏ chuột vào đây rồi Ctrl+V · kéo thả ảnh</span>
      <button class="small" @click="pick">+ Thêm ảnh</button>
    </div>
    <p class="muted small">
      Thêm bao nhiêu nhân vật cũng được (người, linh vật, sản phẩm…). Mỗi ảnh có tên và mô tả riêng ở bên dưới.
      Claude xem ảnh để viết kịch bản. Khi tạo từng cảnh, Canva và Flow dùng ảnh của <strong>những nhân vật có mặt trong
      cảnh đó</strong> làm tham chiếu (tối đa 3 ảnh mỗi cảnh).
    </p>

    <div v-if="pending.length" class="pending-bar row">
      <strong>{{ pending.length }} ảnh chưa lưu</strong>
      <span class="muted small">Đặt tên và mô tả cho từng nhân vật rồi bấm Lưu</span>
      <span class="grow" />
      <button class="small primary" :disabled="pending.some((p) => p.saving)" @click="saveAll">Lưu tất cả</button>
      <button class="small" @click="[...pending].forEach(discard)">Bỏ hết</button>
    </div>

    <div class="grid">
      <div v-for="item in pending" :key="'p' + item.key" class="char new">
        <img :src="item.preview" />
        <label>Tên nhân vật</label>
        <input :ref="(el) => (nameInputs[item.key] = el)" v-model="item.name" placeholder="Ví dụ: Mai" />
        <label>Mô tả</label>
        <textarea v-model="item.description" rows="4"
                  placeholder="Vai trò, tuổi, tính cách, quan hệ với nhân vật khác, chi tiết ảnh không thể hiện…" />
        <div class="row">
          <button class="small primary" :disabled="item.saving" @click="save(item)">Lưu</button>
          <button class="small" @click="discard(item)">Bỏ</button>
        </div>
      </div>

      <div v-for="c in project.characters" :key="c.id" class="char">
        <img v-if="c.file" :src="fileUrl(project.id, 'assets', c.file)" />
        <div v-else class="noimg muted" title="Nhân vật chỉ có mô tả; dán ảnh khi trỏ chuột vào khung này để thêm nhân vật có ảnh">
          Không có ảnh<br /><span class="small">chỉ dùng mô tả</span>
        </div>
        <label>Tên nhân vật</label>
        <input v-model="c.name" @change="update(c)" />
        <label>Mô tả</label>
        <textarea v-model="c.description" rows="4" placeholder="Vai trò, tuổi, tính cách…" @change="update(c)" />
        <div class="row">
          <span class="badge" :title="c.canvaMediaId ? 'Đã có trên Canva' : 'Sẽ tải lên Canva khi một cảnh cần nhân vật này'">
            {{ c.canvaMediaId ? '✔ Canva' : 'chưa lên Canva' }}
          </span>
          <span class="grow" />
          <button class="small danger" @click="remove(c)">Xoá</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 10px; }
.char { background: var(--panel-2); border: 1px solid var(--border); border-radius: 10px; padding: 8px;
        display: flex; flex-direction: column; gap: 4px; }
.char.new { border-color: var(--accent); }
.noimg { width: 100%; aspect-ratio: 1; border-radius: 6px; background: var(--bg); display: flex; flex-direction: column;
         align-items: center; justify-content: center; text-align: center; font-size: 13px; margin-bottom: 4px; }
.char img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 6px; background: var(--bg); margin-bottom: 4px; }
.char label { margin: 2px 0 0; }
.char textarea { min-height: 70px; font-size: 12px; }
.char .row { margin-top: 4px; }
.pending-bar { background: var(--bg); border: 1px dashed var(--accent); border-radius: 8px; padding: 8px 10px; margin-bottom: 10px; }
.panel.dragging { border-color: var(--accent); border-style: dashed; }
.small { font-size: 12px; }
</style>
