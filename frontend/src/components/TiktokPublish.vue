<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { api, fileUrl } from '../api'

const props = defineProps({ project: Object })
const emit = defineEmits(['job', 'error', 'changed'])

const status = ref(null)
const creator = ref(null)
const creatorError = ref('')
const mode = ref('draft')
const tone = ref('')

// Direct-post form. TikTok rules: privacy has NO default; interaction toggles start OFF.
const privacy = ref('')
const allowComment = ref(false)
const allowDuet = ref(false)
const allowStitch = ref(false)
const commercial = ref(false)
const brandOrganic = ref(false)
const brandContent = ref(false)
const isAigc = ref(true)
const consent = ref(false)

const PRIVACY_LABEL = {
  PUBLIC_TO_EVERYONE: 'Mọi người',
  MUTUAL_FOLLOW_FRIENDS: 'Bạn bè (follow lẫn nhau)',
  FOLLOWER_OF_CREATOR: 'Người theo dõi',
  SELF_ONLY: 'Chỉ mình tôi'
}

const canDirect = computed(() => status.value?.scopes?.includes('video.publish'))
const canDraft = computed(() => status.value?.scopes?.includes('video.upload'))
const captionLen = computed(() => [...(props.project.tiktokCaption || '')].length)
const videoUrl = computed(() => props.project.lastRender && fileUrl(props.project.id, 'output', props.project.lastRender, props.project.updatedAt))

const consentText = computed(() => {
  if (commercial.value && brandContent.value) return "Branded Content Policy và Music Usage Confirmation của TikTok"
  return 'Music Usage Confirmation của TikTok'
})

const directReady = computed(() =>
  privacy.value && consent.value && !(commercial.value && !brandOrganic.value && !brandContent.value) &&
  !(brandContent.value && privacy.value === 'SELF_ONLY'))

async function loadStatus() {
  try {
    status.value = await api.tiktokStatus()
    if (!canDraft.value && canDirect.value) mode.value = 'direct'
  } catch (e) {
    status.value = null
  }
}

async function loadCreator() {
  creator.value = null
  creatorError.value = ''
  try {
    creator.value = await api.tiktokCreator()
    if (privacy.value && !creator.value.privacy_level_options?.includes(privacy.value)) privacy.value = ''
  } catch (e) {
    creatorError.value = e.message
  }
}

watch(mode, (m) => { if (m === 'direct') loadCreator() })
watch(brandContent, (v) => { if (v && privacy.value === 'SELF_ONLY') privacy.value = '' })

async function genCaption() {
  try {
    emit('job', await api.tiktokCaption(props.project.id, { tone: tone.value }))
  } catch (e) {
    emit('error', e.message)
  }
}

async function publish() {
  if (!props.project.lastRender) return emit('error', 'Hãy render video trước')
  const body = { mode: mode.value, file: props.project.lastRender, consent: true }
  if (mode.value === 'direct') {
    Object.assign(body, {
      title: props.project.tiktokCaption,
      privacy_level: privacy.value,
      allow_comment: allowComment.value,
      allow_duet: allowDuet.value,
      allow_stitch: allowStitch.value,
      brand_content_toggle: commercial.value && brandContent.value,
      brand_organic_toggle: commercial.value && brandOrganic.value,
      is_aigc: isAigc.value
    })
  } else if (!consent.value) {
    return emit('error', 'Hãy tick xác nhận trước khi gửi')
  }
  try {
    emit('job', await api.tiktokPublish(props.project.id, body))
    consent.value = false
  } catch (e) {
    emit('error', e.message)
  }
}

const refreshing = ref(false)
const inboxPending = computed(() => (props.project.tiktokPosts || []).filter((p) => p.status === 'SEND_TO_USER_INBOX'))

async function refreshPosts() {
  refreshing.value = true
  try {
    const fresh = await api.tiktokRefresh(props.project.id)
    props.project.tiktokPosts = fresh.tiktokPosts
  } catch (e) {
    emit('error', e.message)
  } finally {
    refreshing.value = false
  }
}

async function copyCaption() {
  try { await navigator.clipboard.writeText(props.project.tiktokCaption || '') } catch { /* ignore */ }
}

const statusLabel = (s) => ({
  PROCESSING_UPLOAD: 'Đang tải lên', PROCESSING_DOWNLOAD: 'Đang xử lý', SEND_TO_USER_INBOX: 'Chờ bạn đăng trong app TikTok',
  PUBLISH_COMPLETE: 'Đã đăng', FAILED: 'Lỗi'
}[s] || s)

onMounted(loadStatus)
</script>

<template>
  <div class="panel">
    <h3>4 · Đăng TikTok</h3>

    <label>Mô tả video (caption + hashtag) · {{ captionLen }}/2200</label>
    <textarea v-model="project.tiktokCaption" rows="6" maxlength="2200" @input="emit('changed')"
              placeholder="Bấm “Claude viết mô tả” để Claude viết mô tả từ kịch bản, hoặc tự viết" />
    <div class="row" style="margin-top:6px">
      <input v-model="tone" class="grow" placeholder="Giọng văn (tuỳ chọn): hài hước, cảm động, giáo dục…" />
      <button class="small" @click="genCaption"><Icon name="sparkles" /> Claude viết mô tả</button>
      <button class="small" :disabled="!project.tiktokCaption" title="Chép mô tả" aria-label="Chép mô tả" @click="copyCaption"><Icon name="copy" /> Chép</button>
    </div>

    <hr />

    <div v-if="!status?.configured" class="muted small">
      Chưa cấu hình app TikTok. Vào <RouterLink to="/settings">Cài đặt → TikTok</RouterLink> để nhập Client key và kết nối tài khoản.
    </div>
    <div v-else-if="!status?.connected" class="muted small">
      Chưa kết nối tài khoản TikTok. Vào <RouterLink to="/settings">Cài đặt → TikTok</RouterLink> và bấm Kết nối.
    </div>
    <template v-else>
      <div class="row acct">
        <img v-if="status.avatarUrl" :src="status.avatarUrl" class="avatar" />
        <div class="grow">
          <div>Tài khoản: <strong>{{ creator?.creator_nickname || status.displayName || '—' }}</strong></div>
          <div v-if="creator?.creator_username" class="muted small">@{{ creator.creator_username }}</div>
        </div>
        <button class="small" @click="loadStatus(); mode === 'direct' && loadCreator()">↻</button>
      </div>

      <div class="row modes">
        <label class="row chk"><input v-model="mode" type="radio" value="draft" :disabled="!canDraft" />
          Gửi vào hộp nháp TikTok</label>
        <label class="row chk"><input v-model="mode" type="radio" value="direct" :disabled="!canDirect" />
          Đăng thẳng</label>
      </div>
      <div v-if="!canDirect" class="muted small">Muốn "Đăng thẳng" thì app TikTok cần được duyệt scope <code>video.publish</code>, rồi bật scope đó trong Cài đặt và kết nối lại.</div>

      <video v-if="videoUrl" :src="videoUrl" controls class="preview" />
      <div v-else class="err small">Chưa có video. Hãy Render trước.</div>

      <!-- Draft / inbox -->
      <template v-if="mode === 'draft'">
        <p class="muted small">
          Video được gửi vào TikTok của bạn. Mở app TikTok, bấm vào thông báo trong <strong>Hộp thư</strong> để chỉnh sửa,
          dán mô tả (nút Chép), chọn quyền riêng tư rồi đăng. TikTok cho tối đa 5 video chờ trong 24 giờ.
        </p>
        <label class="row chk"><input v-model="consent" type="checkbox" /> Tôi xác nhận gửi video này lên tài khoản TikTok ở trên</label>
        <button class="primary wide" :disabled="!videoUrl || !consent" @click="publish"><Icon name="send" /> Gửi vào TikTok</button>
      </template>

      <!-- Direct post (TikTok content-sharing UX rules) -->
      <template v-else>
        <div v-if="creatorError" class="err small">{{ creatorError }}</div>
        <template v-if="creator">
          <div class="field">
            <label>Ai có thể xem video này *</label>
            <select v-model="privacy">
              <option value="" disabled>— Chọn quyền riêng tư —</option>
              <option v-for="o in creator.privacy_level_options" :key="o" :value="o"
                      :disabled="brandContent && o === 'SELF_ONLY'">{{ PRIVACY_LABEL[o] || o }}</option>
            </select>
          </div>
          <div class="muted small">Cho phép người xem:</div>
          <div class="row toggles">
            <label class="row chk" :class="{ off: creator.comment_disabled }"><input v-model="allowComment" type="checkbox" :disabled="creator.comment_disabled" /> Bình luận</label>
            <label class="row chk" :class="{ off: creator.duet_disabled }"><input v-model="allowDuet" type="checkbox" :disabled="creator.duet_disabled" /> Duet</label>
            <label class="row chk" :class="{ off: creator.stitch_disabled }"><input v-model="allowStitch" type="checkbox" :disabled="creator.stitch_disabled" /> Stitch</label>
          </div>

          <label class="row chk"><input v-model="commercial" type="checkbox" /> Công khai nội dung thương mại</label>
          <div v-if="commercial" class="sub">
            <label class="row chk"><input v-model="brandOrganic" type="checkbox" /> Thương hiệu của bạn (nhãn "Promotional content")</label>
            <label class="row chk"><input v-model="brandContent" type="checkbox" /> Nội dung hợp tác thương mại (nhãn "Paid partnership")</label>
            <div v-if="!brandOrganic && !brandContent" class="err small">Chọn ít nhất một mục, hoặc tắt công khai nội dung thương mại.</div>
            <div v-if="brandContent" class="muted small">Nội dung hợp tác thương mại không thể để "Chỉ mình tôi".</div>
          </div>

          <label class="row chk"><input v-model="isAigc" type="checkbox" /> Gắn nhãn "Nội dung do AI tạo" (khuyên dùng, vì video được dựng bằng AI)</label>

          <label class="row chk consent"><input v-model="consent" type="checkbox" />
            <span>Khi đăng, bạn đồng ý với
              <a v-if="commercial && brandContent" href="https://www.tiktok.com/legal/page/global/bc-policy/en" target="_blank">Branded Content Policy</a>
              <template v-if="commercial && brandContent"> và </template>
              <a href="https://www.tiktok.com/legal/page/global/music-usage-confirmation/en" target="_blank">Music Usage Confirmation</a>
              của TikTok.</span>
          </label>
          <div class="muted small">Sau khi đăng, TikTok có thể mất vài phút để xử lý trước khi video hiện trên trang cá nhân.</div>
          <button class="primary wide" :disabled="!videoUrl || !directReady" @click="publish"><Icon name="rocket" /> Đăng lên TikTok</button>
        </template>
        <div v-else-if="!creatorError" class="muted small">Đang tải thông tin tài khoản…</div>
      </template>

      <div v-if="inboxPending.length" class="inbox-help">
        <strong class="ih"><Icon name="inbox" /> {{ inboxPending.length }} video đang chờ bạn trong TikTok</strong>
        <p class="small">
          Video gửi bằng "hộp nháp" <u>chưa hiện</u> trên trang cá nhân hay trong Bản nháp cho tới khi bạn hoàn tất:
        </p>
        <ol class="small">
          <li>Mở <strong>app TikTok trên điện thoại</strong>, đăng nhập đúng tài khoản <strong>{{ status.displayName }}</strong>.</li>
          <li>Vào tab <strong>Hộp thư</strong> (Inbox) và mở thông báo về video được gửi từ <strong>Media Studio</strong>.</li>
          <li>Bấm vào thông báo để mở trình chỉnh sửa, dán mô tả (nút Chép ở trên), chọn quyền riêng tư rồi bấm <strong>Đăng</strong>.</li>
        </ol>
        <p class="muted small">Đăng xong, bấm ↻ bên dưới: trạng thái sẽ chuyển sang "Đã đăng".</p>
      </div>

      <div v-if="project.tiktokPosts?.length" class="history">
        <div class="row" style="margin:10px 0 4px">
          <span class="muted small grow">Lịch sử đăng</span>
          <button class="small" :disabled="refreshing" @click="refreshPosts">{{ refreshing ? '…' : '↻ Cập nhật trạng thái' }}</button>
        </div>
        <div v-for="p in project.tiktokPosts" :key="p.publishId" class="row post">
          <span :class="p.status === 'FAILED' ? 'err' : (p.status === 'PUBLISH_COMPLETE' || p.status === 'SEND_TO_USER_INBOX') ? 'ok' : ''">●</span>
          <span class="grow small">{{ p.mode === 'direct' ? 'Đăng' : 'Nháp' }} · {{ p.file }} · {{ statusLabel(p.status) }}<template v-if="p.failReason"> ({{ p.failReason }})</template></span>
          <span class="muted small">{{ new Date(p.createdAt).toLocaleString('vi-VN') }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
hr { border: 0; border-top: 1px solid var(--border); margin: 12px 0; }
.acct { margin-bottom: 8px; }
.avatar { width: 32px; height: 32px; border-radius: 50%; }
.modes { gap: 16px; margin-bottom: 4px; }
.chk { gap: 6px; color: var(--text); font-size: 13px; margin: 4px 0; flex-wrap: nowrap; }
.chk input { width: auto; flex-shrink: 0; }
.chk.off { opacity: .5; }
.toggles { gap: 14px; }
.sub { padding-left: 22px; }
.consent { align-items: flex-start; margin-top: 8px; }
.preview { width: 100%; max-height: 260px; background: #000; border-radius: 8px; margin: 8px 0; }
.wide { width: 100%; margin-top: 8px; }
.post { padding: 3px 0; border-bottom: 1px solid var(--border); }
.inbox-help { background: color-mix(in srgb, var(--ok) 12%, transparent); border: 1px solid var(--ok); border-radius: 8px;
              padding: 10px 12px; margin-top: 12px; }
.inbox-help ol { padding-left: 18px; margin: 6px 0; line-height: 1.7; }
.inbox-help p { margin: 4px 0; }
.small { font-size: 12px; }
code { background: var(--bg); padding: 1px 5px; border-radius: 4px; }
</style>
