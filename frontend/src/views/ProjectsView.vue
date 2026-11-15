<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

const router = useRouter()
const projects = ref([])
const name = ref('')
const aspect = ref('16:9')
const error = ref('')

async function load() {
  try {
    projects.value = await api.projects()
  } catch (e) {
    error.value = 'Không kết nối được backend: ' + e.message
  }
}

async function create() {
  try {
    const p = await api.createProject(name.value, aspect.value)
    router.push(`/project/${p.id}`)
  } catch (e) {
    error.value = e.message
  }
}

async function remove(p) {
  if (!confirm(`Xoá dự án "${p.name}" cùng toàn bộ file của nó?`)) return
  await api.deleteProject(p.id)
  load()
}

const fmt = (d) => new Date(d).toLocaleString('vi-VN')
onMounted(load)
</script>

<template>
  <h1>Dự án</h1>
  <div v-if="error" class="error-bar">{{ error }}</div>

  <div class="panel create">
    <div class="grow">
      <label>Tên dự án</label>
      <input v-model="name" placeholder="VD: Review sản phẩm, Câu chuyện lịch sử…" @keyup.enter="create" />
    </div>
    <div>
      <label>Khung hình</label>
      <select v-model="aspect">
        <option value="16:9">16:9 (YouTube)</option>
        <option value="9:16">9:16 (TikTok / Shorts)</option>
        <option value="1:1">1:1</option>
      </select>
    </div>
    <button class="primary" @click="create">+ Tạo dự án</button>
  </div>

  <div class="list">
    <div v-for="p in projects" :key="p.id" class="panel card" @click="router.push(`/project/${p.id}`)">
      <div class="row">
        <strong class="grow">{{ p.name }}</strong>
        <span class="badge">{{ p.aspect }}</span>
      </div>
      <div class="muted">{{ p.scenes.length }} cảnh · cập nhật {{ fmt(p.updatedAt) }}</div>
      <div class="muted ih" v-if="p.lastRender"><Icon name="film" /> {{ p.lastRender }}</div>
      <div class="row" style="justify-content:flex-end">
        <button class="small danger" @click.stop="remove(p)">Xoá</button>
      </div>
    </div>
    <div v-if="!projects.length && !error" class="muted">Chưa có dự án nào.</div>
  </div>
</template>

<style scoped>
.create { display: flex; gap: 12px; align-items: flex-end; margin-bottom: 20px; }
.list { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.card { cursor: pointer; display: flex; flex-direction: column; gap: 6px; }
.card:hover { border-color: var(--accent); }
@media (max-width: 820px) { .create { flex-wrap: wrap; } .create > * { flex: 1 1 100%; } .list { grid-template-columns: 1fr; } }
</style>
