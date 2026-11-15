<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'

const props = defineProps({ jobId: String })
const emit = defineEmits(['done'])
const lines = ref([])
const status = ref('')
const box = ref(null)
let es = null

function open(id) {
  close()
  lines.value = []
  status.value = ''
  if (!id) return
  es = new EventSource(`/api/jobs/${id}/stream`)
  es.addEventListener('log', (e) => {
    lines.value.push(e.data)
    nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight })
  })
  es.addEventListener('status', (e) => {
    status.value = JSON.parse(e.data).status
    close()
    emit('done', status.value)
  })
  es.onerror = () => close()
}

function close() {
  if (es) { es.close(); es = null }
}

watch(() => props.jobId, open, { immediate: true })
onBeforeUnmount(close)

const cls = (l) => l.startsWith('✖') ? 'err' : l.startsWith('✔') ? 'ok' : l.startsWith('💬') ? 'say' : l.startsWith('🔧') ? 'tool' : ''
</script>

<template>
  <div ref="box" class="log">
    <div v-if="!jobId" class="muted">Chọn một tác vụ để xem log.</div>
    <div v-for="(l, i) in lines" :key="i" :class="['line', cls(l)]">{{ l }}</div>
    <div v-if="status" :class="status === 'DONE' ? 'ok' : 'err'">— {{ status }} —</div>
  </div>
</template>

<style scoped>
.log {
  background: #0a0c10; border: 1px solid var(--border); border-radius: 8px; padding: 10px;
  font-family: Consolas, 'Cascadia Mono', monospace; font-size: 12px; height: 320px; overflow: auto;
}
.line { white-space: pre-wrap; word-break: break-word; margin-bottom: 2px; color: #c5cad6; }
.say { color: #e6e8ee; }
.tool { color: #9fb7ff; }
</style>
