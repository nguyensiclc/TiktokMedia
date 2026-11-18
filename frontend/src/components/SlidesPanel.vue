<script setup>
import { ref, computed, watch } from 'vue'
import { api, fileUrl } from '../api'
import { ask } from '../confirm'

/** "List ảnh" mode: one image per scene to swipe through, instead of a video. */
const props = defineProps({ project: Object, version: String, busy: Boolean })
const emit = defineEmits(['make', 'cover', 'studio', 'caption'])

// Post to TikTok Studio through the browser (same Chrome profile as Google Flow, no TikTok API).
const VIEW = [
  { id: 'SELF_ONLY', label: 'Chỉ mình tôi' },
  { id: 'FRIENDS', label: 'Bạn bè' },
  { id: 'PUBLIC', label: 'Mọi người' },
]
const privacy = ref('SELF_ONLY')
const aiLabel = ref(true)
const caption = ref(props.project.tiktokCaption || '')
watch(() => props.project.tiktokCaption, (c) => { if (c) caption.value = c })
const tooMany = computed(() => (props.project.slideFiles || []).length > 35)
async function openLogin() {
  try {
    const r = await api.tiktokStudioLogin()
    msg.value = r.hint
  } catch (e) {
    msg.value = 'Không mở được Chrome: ' + e.message
  }
}
async function studio() {
  const n = Math.min(files.value.length, 35)
  const who = VIEW.find((v) => v.id === privacy.value).label
  const ok = await ask({
    title: `Đăng ${n} ảnh lên TikTok?`,
    message: `Media Studio sẽ mở TikTok Studio trong Chrome, tải ${n} ảnh lên, điền caption và bấm Đăng với chế độ "${who}".`
      + (privacy.value === 'PUBLIC' ? ' Bài sẽ hiện công khai ngay.' : ''),
    confirmLabel: 'Đăng', icon: 'send',
  })
  if (ok) emit('studio', { privacy: privacy.value, caption: caption.value, aiLabel: aiLabel.value })
}

const withText = ref(true)
// Cover slide text (first picture). Empty = Claude writes it when exporting.
const coverTitle = ref(props.project.coverTitle || '')
const coverTeaser = ref(props.project.coverTeaser || '')
watch(() => [props.project.coverTitle, props.project.coverTeaser], ([t, te]) => {
  coverTitle.value = t || ''
  coverTeaser.value = te || ''
})
// Photo size: 3:4 / 1:1 are shown whole in the middle of the TikTok screen, away from the search bar and caption.
const RATIOS = [
  { id: '3:4', label: '3:4', hint: 'Khuyên dùng: TikTok hiện trọn ảnh giữa màn hình, chữ và ảnh không bị thanh tìm kiếm hay caption che.' },
  { id: '1:1', label: '1:1 vuông', hint: 'Ảnh vuông, an toàn nhất nhưng ảnh nhỏ hơn và bị cắt nhiều hơn.' },
  { id: 'video', label: 'Như video', hint: 'Cùng khung với video (9:16): đầy màn hình nhưng TikTok che phần trên và dưới.' },
]
const ratio = ref(props.project.slideRatio || '3:4')
const ratioHint = computed(() => (RATIOS.find((r) => r.id === ratio.value) || RATIOS[0]).hint)
function remakeCover() {
  emit('cover', { coverTitle: coverTitle.value, coverTeaser: coverTeaser.value })
}
function make() {
  emit('make', withText.value, { coverTitle: coverTitle.value, coverTeaser: coverTeaser.value, ratio: ratio.value })
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
      Mỗi cảnh thành một ảnh JPG. Dùng để đăng dạng ảnh/carousel, người xem đọc xong vuốt sang ảnh tiếp theo.
      Nếu dự án có nhạc nền, nhạc được cắt từ điểm bắt đầu đã chọn và đi kèm trong bộ tải về.
    </p>
    <div class="ratio">
      <span class="muted small" id="ratio-lbl">Khung ảnh</span>
      <div class="seg" role="radiogroup" aria-labelledby="ratio-lbl">
        <label v-for="r in RATIOS" :key="r.id" :class="{ on: ratio === r.id }">
          <input v-model="ratio" type="radio" name="slide-ratio" :value="r.id" /> {{ r.label }}
        </label>
      </div>
      <p class="muted small hint">{{ ratioHint }}</p>
    </div>
    <label class="row chk"><input v-model="withText" type="checkbox" /> Kèm chữ (dải chữ riêng phía trên ảnh, không đè lên ảnh)</label>
    <div class="cover">
      <div class="muted small"><Icon name="sparkles" /> Trang bìa (ảnh đầu tiên). Để trống thì Claude tự viết khi xuất.</div>
      <input v-model="coverTitle" placeholder="Tên truyện, VD: Sự tích trầu cau" maxlength="80" />
      <input v-model="coverTeaser" placeholder="Câu gợi tò mò, VD: Vì sao người em hoá thành tảng đá bên suối?" maxlength="140" />
      <button v-if="(project.slideFiles || []).length" class="small" :disabled="busy" @click="remakeCover"
              title="Chỉ làm lại ảnh bìa, giữ nguyên các ảnh cảnh đã xuất">
        <Icon name="sparkles" /> Tạo lại riêng ảnh bìa
      </button>
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
      <div class="studio">
        <h4><Icon name="send" /> Tự đăng lên TikTok Studio</h4>
        <p class="muted small">
          Mở TikTok Studio trong Chrome riêng của Media Studio, tải các ảnh theo thứ tự, điền caption rồi bấm Đăng.
          Không dùng API của TikTok. Gặp captcha hay bước xác minh thì dừng lại để bạn tự xử lý.
        </p>
        <div class="row">
          <button class="small" type="button" @click="openLogin"><Icon name="key" /> Mở Chrome để đăng nhập TikTok</button>
          <span class="muted small grow">Chỉ cần làm một lần. Đăng nhập xong hãy ĐÓNG cửa sổ Chrome đó rồi mới bấm Đăng.</span>
        </div>
        <p v-if="tooMany" class="warn small">TikTok cho tối đa 35 ảnh mỗi bài: chỉ 35 ảnh đầu được đăng.</p>
        <div class="row" style="justify-content:space-between">
          <label class="small" style="margin:0">Caption và hashtag</label>
          <button class="small" type="button" :disabled="busy" @click="emit('caption')"><Icon name="sparkles" /> Claude viết caption</button>
        </div>
        <textarea v-model="caption" rows="4" maxlength="2200" placeholder="Câu mở đầu, vài dòng tóm tắt, lời mời theo dõi, #hashtag" />
        <div class="ratio">
          <span class="muted small" id="view-lbl">Ai được xem</span>
          <div class="seg" role="radiogroup" aria-labelledby="view-lbl">
            <label v-for="v in VIEW" :key="v.id" :class="{ on: privacy === v.id }">
              <input v-model="privacy" type="radio" name="tt-view" :value="v.id" /> {{ v.label }}
            </label>
          </div>
        </div>
        <label class="row chk"><input v-model="aiLabel" type="checkbox" /> Gắn nhãn "Nội dung do AI tạo" (ảnh do AI vẽ)</label>
        <button class="primary wide" style="margin-top:8px" :disabled="busy" @click="studio">
          <Icon name="send" /> Đăng {{ Math.min(files.length, 35) }} ảnh lên TikTok
        </button>
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
.studio { display: grid; gap: 8px; margin: 10px 0; padding: 12px; border: 1px solid var(--border); border-radius: 10px; }
.studio h4 { margin: 0; font-size: 14px; display: flex; align-items: center; gap: 6px; }
.studio p { margin: 0; }
.studio .ratio { margin: 0; }
.warn { color: var(--warn); }
.ratio { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin-bottom: 8px; }
.ratio .hint { flex-basis: 100%; margin: 0; }
.seg { display: flex; gap: 2px; padding: 2px; border-radius: 8px; background: var(--fill); }
.seg label { display: inline-flex; align-items: center; padding: 4px 12px; border-radius: 6px; font-size: 13px; cursor: pointer; }
.seg label.on { background: var(--panel); box-shadow: 0 1px 2px rgba(0, 0, 0, .14), 0 0 0 .5px rgba(0, 0, 0, .06); font-weight: 600; }
.seg input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.seg label:has(input:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
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
.th img { width: 100%; aspect-ratio: 3 / 4; object-fit: cover; display: block; }
.th span { position: absolute; left: 0; right: 0; bottom: 0; text-align: center; font-size: 11px; background: rgba(0,0,0,.65); color: #fff; }
.small { font-size: 12px; }
</style>
