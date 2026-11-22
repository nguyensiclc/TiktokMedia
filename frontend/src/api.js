// Thin wrapper around the Spring Boot API. Same origin in production, proxied by Vite in dev.
async function request(method, url, body, isForm = false) {
  const opts = { method, headers: {} }
  if (body !== undefined) {
    if (isForm) opts.body = body
    else {
      opts.headers['Content-Type'] = 'application/json'
      opts.body = JSON.stringify(body)
    }
  }
  const res = await fetch(url, opts)
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`)
  return data
}

export const api = {
  get: (u) => request('GET', u),
  post: (u, b = {}) => request('POST', u, b),
  put: (u, b) => request('PUT', u, b),
  del: (u) => request('DELETE', u),
  upload: (u, form) => request('POST', u, form, true),

  projects: () => api.get('/api/projects'),
  project: (id) => api.get(`/api/projects/${id}`),
  createProject: (name, aspect) => api.post('/api/projects', { name, aspect }),
  saveProject: (p) => api.put(`/api/projects/${p.id}`, p),
  deleteProject: (id) => api.del(`/api/projects/${id}`),

  storyboard: (id, body) => api.post(`/api/projects/${id}/storyboard`, body),
  drama: (id, step, body) => api.post(`/api/projects/${id}/drama/${step}`, body),
  flow: (id, sceneId, mediaType) => api.post(`/api/projects/${id}/scenes/${sceneId}/flow`, { mediaType }),
  flowAll: (id, mediaType = 'image', onlyMissing = true) => api.post(`/api/projects/${id}/flow-all`, { mediaType, onlyMissing }),
  canva: (id, sceneId) => api.post(`/api/projects/${id}/scenes/${sceneId}/canva`),
  // "Flow" buttons: the backend uses the local image service or the extension queue (Settings → flowEngine).
  // For the extension, tell it (its app-bridge script listens on this page) to open Flow and start.
  flowExt: async (id, sceneIds = null) => {
    const job = await api.post(`/api/projects/${id}/flow-gen`, sceneIds ? { sceneIds } : { onlyMissing: true })
    if (job?.type === 'flow') window.postMessage({ source: 'media-studio', type: 'flow-ext-queued' }, window.location.origin)
    return job
  },
  flowApiHealth: () => api.get('/api/flow-api/health'),
  gemini: (id, sceneId) => api.post(`/api/projects/${id}/scenes/${sceneId}/gemini`),
  geminiAll: (id, onlyMissing = true) => api.post(`/api/projects/${id}/gemini-all`, { onlyMissing }),
  canvaAll: (id, onlyMissing = true) => api.post(`/api/projects/${id}/canva-all`, { onlyMissing }),
  styleRef: (id, sceneId) => api.post(`/api/projects/${id}/style-ref`, { sceneId }),
  voice: (id, body) => api.post(`/api/projects/${id}/voice`, body),
  agent: (id, prompt) => api.post(`/api/projects/${id}/agent`, { prompt }),
  render: (id) => api.post(`/api/projects/${id}/render`),
  slides: (id, withText = true, cover = {}) => api.post(`/api/projects/${id}/slides`, { withText, ...cover }),
  slidesCover: (id, cover = {}) => api.post(`/api/projects/${id}/slides/cover`, cover),
  uploadScene: (id, sceneId, kind, file) => {
    const f = new FormData()
    f.append('kind', kind)
    f.append('file', file)
    return api.upload(`/api/projects/${id}/scenes/${sceneId}/upload`, f)
  },

  addCharacter: (id, file, name, description = '') => {
    const f = new FormData()
    f.append('file', file)
    f.append('name', name)
    f.append('description', description)
    return api.upload(`/api/projects/${id}/characters`, f)
  },
  updateCharacter: (id, cid, body) => api.put(`/api/projects/${id}/characters/${cid}`, body),
  setCharacterImage: (id, cid, file) => {
    const f = new FormData()
    f.append('file', file)
    return api.upload(`/api/projects/${id}/characters/${cid}/image`, f)
  },
  deleteCharacter: (id, cid) => api.del(`/api/projects/${id}/characters/${cid}`),
  portraits: (id, ids = null) => api.post(`/api/projects/${id}/characters/portraits`, ids ? { ids } : {}),

  openFolder: (id, folder = 'output') => api.post(`/api/projects/${id}/open-folder`, { folder }),
  setMusic: (id, file) => {
    const f = new FormData()
    f.append('file', file)
    return api.upload(`/api/projects/${id}/music`, f)
  },
  deleteMusic: (id) => api.del(`/api/projects/${id}/music`),
  addSources: (id, files) => {
    const f = new FormData()
    files.forEach((file) => f.append('files', file))
    return api.upload(`/api/projects/${id}/sources`, f)
  },
  deleteSource: (id, name) => api.del(`/api/projects/${id}/sources/${encodeURIComponent(name)}`),
  analyze: (id, note, storyboard = true, ai = 'claude', model = '') =>
    api.post(`/api/projects/${id}/analyze`, { note, storyboard, ai, model }),

  tiktokStatus: () => api.get('/api/tiktok/status'),
  tiktokLogin: () => api.post('/api/tiktok/login'),
  tiktokLogout: () => api.post('/api/tiktok/logout'),
  tiktokCreator: () => api.get('/api/tiktok/creator-info'),
  tiktokCaption: (id, body = {}) => api.post(`/api/projects/${id}/tiktok/caption`, body),
  tiktokPublish: (id, body) => api.post(`/api/projects/${id}/tiktok/publish`, body),
  tiktokStudioLogin: () => api.post('/api/system/tiktok-login'),
  tiktokStudioPost: (id, body) => api.post(`/api/projects/${id}/tiktok/studio-post`, body),
  tiktokRefresh: (id) => api.post(`/api/projects/${id}/tiktok/refresh`),

  clearMedia:(id, sceneId, kind) => api.del(`/api/projects/${id}/scenes/${sceneId}/media?kind=${kind}`),

  jobs: (projectId) => api.get(`/api/jobs?projectId=${projectId}`),
  cancelJob: (id) => api.post(`/api/jobs/${id}/cancel`),

  settings: () => api.get('/api/settings'),
  saveSettings: (s) => api.put('/api/settings', s),
  voiceProfiles: () => api.get('/api/voice/profiles'),
  check: () => api.get('/api/system/check'),
  mcpLogin: () => api.post('/api/system/mcp-login'),
  flowLogin: () => api.post('/api/system/flow-login'),
  flowStatus: () => api.get('/api/system/flow-status'),
  flowCheck: () => api.post('/api/system/flow-check')
}

export const fileUrl = (projectId, folder, name, bust = '') =>
  `/api/projects/${projectId}/files/${folder}/${encodeURIComponent(name)}${bust ? `?v=${bust}` : ''}`

export const isVideo = (name) => /\.(mp4|mov|webm|mkv|m4v)$/i.test(name || '')
