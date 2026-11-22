<script setup>
// Chat window of the assistant (Claude CLI on the server that drives the app). Floating button on every page;
// full screen on a phone. The reply streams over the job SSE as JSON lines: {k:text|tool|changed, …}.
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'

const route = useRoute()
const open = ref(false)
try { open.value = localStorage.getItem('assistantOpen') === '1' } catch { /* ignore */ }
watch(open, (v) => {
  try { localStorage.setItem('assistantOpen', v ? '1' : '0') } catch { /* ignore */ }
  if (v) nextTick(() => { scrollDown(); input.value?.focus() })
})

const messages = ref([])
const jobId = ref(null)
const cost = ref(0)
const text = ref('')
const error = ref('')
const list = ref(null)
const input = ref(null)
let es = null

const projectId = computed(() => (route.path.startsWith('/project/') ? route.params.id : null))
const busy = computed(() => !!jobId.value)

const examples = [
  'Làm video lịch sử về trận Bạch Đằng năm 938',
  'Dự án này còn cảnh nào chưa có hình?',
  'Tạo giọng đọc rồi xuất video cho dự án này',
  'Lên lịch đăng 3 truyện cổ tích, mỗi ngày 1 bài lúc 20h',
]

function scrollDown() {
  nextTick(() => { if (list.value) list.value.scrollTop = list.value.scrollHeight })
}

async function load() {
  try {
    const c = await api.get('/api/assistant/chat')
    messages.value = c.messages || []
    cost.value = c.costUsd || 0
    jobId.value = c.jobId || null
    if (c.jobId) follow(c.jobId)
    scrollDown()
  } catch (e) {
    error.value = e.message
  }
}

function follow(id) {
  es?.close()
  jobId.value = id
  // The stream replays every line from the start: rebuild the reply being written.
  let reply = messages.value[messages.value.length - 1]
  if (!reply || reply.role !== 'assistant') {
    reply = { role: 'assistant', text: '', tools: [] }
    messages.value.push(reply)
  }
  reply.text = ''
  reply.tools = []
  es = new EventSource(`/api/jobs/${id}/stream`)
  es.addEventListener('log', (e) => {
    let ev
    try { ev = JSON.parse(e.data) } catch { return }
    const r = messages.value[messages.value.length - 1]
    if (ev.k === 'text') r.text = r.text ? `${r.text}\n\n${ev.v}` : ev.v
    else if (ev.k === 'tool') r.tools.push(ev.v)
    else if (ev.k === 'changed') window.dispatchEvent(new CustomEvent('ms:refresh', { detail: { projectId: ev.projectId } }))
    scrollDown()
  })
  es.addEventListener('status', async () => {
    es.close(); es = null
    jobId.value = null
    await load()
    window.dispatchEvent(new CustomEvent('ms:refresh', { detail: { projectId: '*' } }))
  })
  es.onerror = () => {
    // Connection dropped (server restart, phone asleep): read the saved state again shortly.
    es?.close(); es = null
    setTimeout(load, 3000)
  }
}

async function send(t = text.value) {
  t = t.trim()
  if (!t || busy.value) return
  error.value = ''
  try {
    const job = await api.post('/api/assistant/chat', { text: t, projectId: projectId.value })
    text.value = ''
    messages.value.push({ role: 'user', text: t, tools: [] })
    messages.value.push({ role: 'assistant', text: '', tools: [] })
    follow(job.id)
    scrollDown()
  } catch (e) {
    error.value = e.message
  }
}

async function stop() {
  try { await api.post('/api/assistant/cancel') } catch (e) { error.value = e.message }
}

async function reset() {
  if (busy.value) await stop()
  es?.close(); es = null
  try {
    await api.del('/api/assistant/chat')
    jobId.value = null
    messages.value = []
    cost.value = 0
  } catch (e) {
    error.value = e.message
  }
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

// Minimal markdown: escape first, then **bold**, `code`, links and line breaks.
function md(s) {
  const esc = (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc
    .replace(/```([\s\S]*?)```/g, (_, c) => `<pre>${c.trim()}</pre>`)
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener">$1</a>')
    .replace(/#\/project\/([a-z0-9-]+)/gi, '<a href="#/project/$1">dự án $1</a>')
    .replace(/^#{1,4} (.+)$/gm, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}

// Messages can also arrive from Telegram: look for them while the window is open (cheap GET, skipped while streaming).
let poll = null
const refresh = () => { if (!es && !busy.value) load() }
onMounted(() => {
  load()
  window.addEventListener('focus', refresh)
  poll = setInterval(() => { if (open.value && !document.hidden) refresh() }, 10000)
})
onBeforeUnmount(() => {
  es?.close()
  clearInterval(poll)
  window.removeEventListener('focus', refresh)
})
</script>

<template>
  <button v-if="!open" class="fab primary" title="Trợ lý" aria-label="Mở trợ lý" @click="open = true">
    <Icon name="chat" :size="22" />
    <span v-if="busy" class="dot" aria-hidden="true" />
  </button>

  <section v-else class="chat" role="dialog" aria-label="Trợ lý Media Studio">
    <header>
      <span class="avatar"><Icon name="bot" :size="16" /></span>
      <div class="title">
        <strong>Trợ lý</strong>
        <span class="muted small">{{ busy ? 'Đang làm…' : projectId ? 'Đang xem dự án này' : 'Claude điều khiển app' }}</span>
      </div>
      <span class="grow" />
      <button class="small icon-only" title="Cuộc trò chuyện mới" aria-label="Cuộc trò chuyện mới" @click="reset"><Icon name="reset" /></button>
      <button class="small icon-only" title="Thu nhỏ" aria-label="Thu nhỏ" @click="open = false"><Icon name="x" /></button>
    </header>

    <div ref="list" class="msgs" aria-live="polite">
      <div v-if="!messages.length" class="empty">
        <p>Chào bạn! Hãy nói mình muốn làm gì, trợ lý sẽ tự điều khiển app: tạo dự án, viết kịch bản, tạo hình, giọng đọc, xuất video và đăng TikTok.</p>
        <button v-for="ex in examples" :key="ex" class="ex" @click="send(ex)">{{ ex }}</button>
      </div>
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="[m.role, { err: m.error }]">
        <div v-if="m.tools?.length" class="tools">
          <span v-for="(t, j) in m.tools" :key="j" class="tool"><Icon name="check" :size="12" /> {{ t }}</span>
        </div>
        <div v-if="m.text" class="bubble" v-html="md(m.text)" />
        <span v-if="m.role === 'user' && m.via === 'telegram'" class="via muted">qua Telegram</span>
        <div v-else-if="m.role === 'assistant' && busy && i === messages.length - 1" class="bubble typing">
          <Icon name="loader" :size="14" /> Đang suy nghĩ…
        </div>
      </div>
    </div>

    <p v-if="error" class="error small">{{ error }}</p>
    <footer>
      <textarea ref="input" v-model="text" rows="2" placeholder="Nhắn cho trợ lý… (Enter để gửi, Shift+Enter xuống dòng)"
                aria-label="Tin nhắn" @keydown="onKey" />
      <button v-if="busy" class="danger icon-only" title="Dừng" aria-label="Dừng" @click="stop"><Icon name="stop" /></button>
      <button v-else class="primary icon-only" :disabled="!text.trim()" title="Gửi" aria-label="Gửi" @click="send()"><Icon name="send" /></button>
    </footer>
    <div v-if="cost" class="cost muted">Chi phí cuộc trò chuyện ≈ ${{ cost.toFixed(3) }}</div>
  </section>
</template>

<style scoped>
.fab { position: fixed; right: 20px; bottom: 20px; z-index: 60; width: 52px; height: 52px; border-radius: 50%;
  display: grid; place-items: center; padding: 0; box-shadow: var(--shadow-3); }
.dot { position: absolute; top: 6px; right: 6px; width: 10px; height: 10px; border-radius: 50%; background: var(--warn);
  border: 2px solid var(--panel); }
.chat { position: fixed; right: 20px; bottom: 20px; z-index: 60; width: 400px; height: min(640px, calc(100dvh - 40px));
  display: flex; flex-direction: column; background: var(--panel); border: 1px solid var(--border); border-radius: var(--r-lg);
  box-shadow: var(--shadow-3); overflow: hidden; }
header { display: flex; align-items: center; gap: var(--s2); padding: 10px 12px; border-bottom: 1px solid var(--border); }
.avatar { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; color: var(--on-accent);
  background: var(--accent); flex-shrink: 0; }
.title { display: flex; flex-direction: column; line-height: 1.25; }
.grow { flex: 1; }
.msgs { flex: 1; overflow-y: auto; padding: var(--s3); display: flex; flex-direction: column; gap: var(--s3); }
.empty p { margin: 0 0 var(--s3); color: var(--muted); line-height: 1.5; }
.ex { display: block; width: 100%; text-align: left; margin-bottom: 6px; white-space: normal; height: auto; padding: 8px 10px; }
.msg { display: flex; flex-direction: column; gap: 4px; max-width: 92%; }
.msg.user { align-self: flex-end; align-items: flex-end; }
.msg.assistant { align-self: flex-start; }
.bubble { padding: 8px 11px; border-radius: 14px; line-height: 1.5; word-break: break-word; }
.user .bubble { background: var(--accent); color: var(--on-accent); border-bottom-right-radius: 4px; }
.assistant .bubble { background: var(--fill); border-bottom-left-radius: 4px; }
.err .bubble { background: var(--err-soft); }
.bubble :deep(code) { font-size: 12px; padding: 1px 4px; border-radius: 4px; background: var(--fill-strong); }
.bubble :deep(pre) { white-space: pre-wrap; font-size: 12px; margin: 4px 0; }
.bubble :deep(a) { color: inherit; text-decoration: underline; }
.typing { display: flex; align-items: center; gap: 6px; color: var(--muted); }
.tools { display: flex; flex-wrap: wrap; gap: 4px; }
.tool { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; color: var(--muted); padding: 2px 7px;
  border: 1px solid var(--border); border-radius: var(--r-full); }
.tool :deep(.ico) { color: var(--ok); }
.error { color: var(--err); margin: 0; padding: 0 var(--s3); }
footer { display: flex; gap: var(--s2); align-items: flex-end; padding: 10px 12px; border-top: 1px solid var(--border); }
footer textarea { flex: 1; resize: none; min-height: 40px; max-height: 140px; font: inherit; }
.via { font-size: 10px; }
.cost { font-size: 11px; text-align: center; padding: 0 0 6px; }

@media (max-width: 820px) {
  .fab { right: calc(14px + env(safe-area-inset-right)); bottom: calc(14px + env(safe-area-inset-bottom)); }
  .chat { inset: 0; width: auto; height: 100dvh; border-radius: 0; border: 0;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left); }
  footer textarea { font-size: 16px; } /* no zoom on iOS */
}
</style>
