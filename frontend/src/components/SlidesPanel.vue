<script setup>
import { ref, computed, watch } from 'vue'
import { api, fileUrl } from '../api'

/** "List ảnh" mode: one image per scene to swipe through, instead of a video. */
const props = defineProps({ project: Object, version: String, busy: Boolean })
const emit = defineEmits(['make'])

const withText = ref(true)
// Cover slide text (first picture). Empty = Claude writes it when exporting.
const coverTitle = ref(props.project.coverTitle || '')
const coverTeaser = ref(props.project.coverTeaser || '')
watch(() => [props.project.coverTitle, props.project.coverTeaser], ([t, te]) => {
  coverTitle.value = t || ''
  coverTeaser.value = te || ''
})
function make() {
  emit('make', withText.value, { coverTitle: coverTitle.value, coverTeaser: coverTeaser.value })
}
const strip = ref(null)
const cur = ref(0)

const files = computed(() => props.project.slideFiles || [])
const url = (f) => fileUrl(props.project.id, 'output', f, props.version)
// Same file with ?download so the browser saves it instead of opening it.
const dl = (f) => `/api/projects/${props.project.id}/files/output/${encodeURIComponent(f)}?download=1`
const msg = ref('')
async function openFolder() {
  try {
    const r = await api.openFolder(props.project.id, 'output')
    msg.value = 'Đã mở thư mục: ' + r.path
  } catch (e) {
    msg.value = 'Không mở được thư mục: ' + e.message
  }
}

function onScroll() {
  const el = strip.value
  if (el) cur.value = Math.round(el.scrollLeft / el.clientWidth)
}

function go(d) {
  const el = strip.value
  if (el) el.scrollTo({ left: (cur.value + d) * el.clientWidth, behavior: 'smooth' })
}
</script>

<template>
  <div class="panel">
    <h3>3b · Danh sách ảnh (người xem vuốt qua)</h3>
    <p class="muted small" style="margin:0 0 8px">
      Mỗi cảnh thành một ảnh JPG, cùng kích thước với video. Dùng để đăng dạng ảnh/carousel, người xem đọc xong vuốt sang ảnh tiếp theo.
      Nếu dự án có nhạc nền, nhạc được cắt từ điểm bắt đầu đã chọn và đi kèm trong bộ tải về.
    </p>
    <label class="row chk"><input v-model="withText" type="checkbox" /> Kèm chữ trên ảnh (đúng vị trí và màu như chế độ script)</label>
    <div class="cover">
      <div class="muted small"><Icon name="sparkles" /> Trang bìa (ảnh đầu tiên). Để trống thì Claude tự viết khi xuất.</div>
      <input v-model="coverTitle" placeholder="Tên truyện, VD: Sự tích trầu cau" maxlength="80" />
      <input v-model="coverTeaser" placeholder="Câu gợi tò mò, VD: Vì sao người em hoá thành tảng đá bên suối?" maxlength="140" />
    </div>
    <button class="primary wide" style="margin-top:8px" :disabled="busy" @click="make">
      <Icon name="image" /> {{ busy ? 'Đang xuất…' : 'Xuất danh sách ảnh' }}
    </button>

    <template v-if="files.length">
      <div ref="strip" class="strip" @scroll.passive="onScroll">
        <img v-for="f in files" :key="f" :src="url(f)" loading="lazy" />
      </div>
      <div class="row nav">
        <button class="small" :disabled="cur <= 0" @click="go(-1)">‹</button>
        <span class="grow muted small" style="text-align:center">Ảnh {{ cur + 1 }} / {{ files.length }}</span>
        <button class="small" :disabled="cur >= files.length - 1" @click="go(1)">›</button>
      </div>
      <div class="row" style="margin:8px 0">
        <a v-if="project.slidesZip" class="btn primary" :href="dl(project.slidesZip)" :download="project.slidesZip">⬇ Tải cả bộ ({{ files.length }} ảnh, .zip)</a>
        <button class="small" @click="openFolder"><Icon name="folder-open" /> Mở thư mục chứa ảnh</button>
      </div>
      <div class="thumbs">
        <a v-for="(f, i) in files" :key="f" class="th" :href="dl(f)" :download="f" :title="'Tải ảnh ' + (i + 1)">
          <img :src="url(f)" loading="lazy" /><span>{{ i + 1 }} ⬇</span>
        </a>
      </div>
      <div v-if="project.slidesMusic" class="music">
        <div class="muted small"><Icon name="music" /> Nhạc nền đã cắt từ giây {{ project.musicStart || 0 }} (có trong file zip)</div>
        <div class="row">
          <audio :src="url(project.slidesMusic)" controls class="grow" />
          <a class="btn" :href="dl(project.slidesMusic)" :download="project.slidesMusic">⬇ Nhạc</a>
        </div>
      </div>
      <div class="muted small">Bấm một ảnh để tải riêng. Thứ tự 1, 2, 3… là thứ tự nên chọn khi đăng. Tải về máy tính rồi upload lên TikTok Studio, hoặc chuyển sang điện thoại.</div>
      <div v-if="msg" class="muted small">{{ msg }}</div>
    </template>
  </div>
</template>

<style scoped>
.chk { gap: 6px; font-size: 13px; color: var(--text); margin: 0; }
.chk input { width: auto; }
.wide { width: 100%; }
.cover { display: grid; gap: 6px; margin-top: 10px; padding: 10px; border: 1px solid var(--border); border-radius: 10px; }
.strip { display: flex; overflow-x: auto; scroll-snap-type: x mandatory; margin-top: 10px; border-radius: 8px;
         background: #000; }
.strip img { flex: 0 0 100%; width: 100%; max-height: 520px; object-fit: contain; scroll-snap-align: center; }
.nav { margin: 6px 0; }
.btn { display: inline-block; padding: 7px 12px; border-radius: 8px; font-size: 13px; color: #fff; }
.btn.primary { background: var(--accent); }
.btn { background: var(--panel-2); border: 1px solid var(--border); }
.music { margin: 6px 0; }
.music audio { height: 32px; min-width: 0; }
.thumbs { display: grid; grid-template-columns: repeat(auto-fill, minmax(72px, 1fr)); gap: 6px; margin-bottom: 6px; }
.th { position: relative; display: block; border-radius: 6px; overflow: hidden; border: 1px solid var(--border); }
.th img { width: 100%; aspect-ratio: 9 / 16; object-fit: cover; display: block; }
.th span { position: absolute; left: 0; right: 0; bottom: 0; text-align: center; font-size: 11px; background: rgba(0,0,0,.65); color: #fff; }
.small { font-size: 12px; }
</style>
