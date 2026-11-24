#!/usr/bin/env node
// Media Studio MCP server for the chat assistant (stdio, JSON-RPC, no dependencies; Node 18+).
// It only talks to the app's own REST API at APP_URL, so the assistant can drive the app but nothing else.
// Env: APP_URL (http://127.0.0.1:8787), APP_RESTRICTED=1 (the chat comes from another device without full rights).

const APP = (process.env.APP_URL || 'http://127.0.0.1:8787').replace(/\/$/, '')
const RESTRICTED = process.env.APP_RESTRICTED === '1'
const MAX_TEXT = 40000

const TOOLS = [
  {
    name: 'api',
    description: 'Gọi REST API của Media Studio. path phải bắt đầu bằng /api/. Trả về JSON (hoặc văn bản). ' +
      'Các tác vụ dài (kịch bản, hình, giọng, render…) trả về một Job {id,status}: dùng wait_job để chờ.',
    inputSchema: {
      type: 'object',
      properties: {
        method: { type: 'string', enum: ['GET', 'POST', 'PUT', 'DELETE'] },
        path: { type: 'string', description: 'vd: /api/projects hoặc /api/projects/ab12cd34/storyboard' },
        body: { type: 'object', description: 'JSON body cho POST/PUT (bỏ trống nếu không cần)' },
      },
      required: ['method', 'path'],
    },
  },
  {
    name: 'wait_job',
    description: 'Chờ một job chạy xong (hoặc hết thời gian chờ) rồi trả về trạng thái và các dòng log cuối. ' +
      'Nếu vẫn RUNNING khi hết giờ, gọi lại để chờ tiếp.',
    inputSchema: {
      type: 'object',
      properties: {
        jobId: { type: 'string' },
        maxSeconds: { type: 'number', description: 'Tối đa bao lâu (mặc định 300, tối đa 1500)' },
        tail: { type: 'number', description: 'Số dòng log cuối trả về (mặc định 25)' },
      },
      required: ['jobId'],
    },
  },
  {
    name: 'view_image',
    description: 'Xem một hình của dự án (để kiểm tra hình cảnh, ảnh bìa…). folder: assets hoặc output.',
    inputSchema: {
      type: 'object',
      properties: {
        projectId: { type: 'string' },
        folder: { type: 'string', enum: ['assets', 'output'] },
        name: { type: 'string', description: 'Tên file, vd scene-ab12-flow.png (lấy từ mediaFile của cảnh)' },
      },
      required: ['projectId', 'folder', 'name'],
    },
  },
  {
    name: 'send_file',
    description: 'Gửi file của dự án (video, hình, bộ ảnh lướt, zip) cho người dùng qua Telegram. ' +
      'Nhiều hình/video (names) được gửi thành album, mỗi album tối đa 10. Giới hạn 50 MB mỗi file.',
    inputSchema: {
      type: 'object',
      properties: {
        projectId: { type: 'string' },
        folder: { type: 'string', enum: ['assets', 'output'] },
        name: { type: 'string', description: 'Một file, vd project.lastRender' },
        names: { type: 'array', items: { type: 'string' }, description: 'Nhiều file, vd project.slideFiles' },
        caption: { type: 'string' },
      },
      required: ['projectId', 'folder'],
    },
  },
]

// Paths the assistant may never call: itself (would loop) and the phone PIN.
const ALWAYS_BLOCKED = [/^\/api\/assistant(\/|$)/, /^\/api\/lan(\/|$)/]
// Same limits the server puts on other devices that only have the PIN.
const REMOTE_BLOCKED = [
  (m, p) => p.startsWith('/api/system/'),
  (m, p) => p.startsWith('/api/flow-ext/'),
  (m, p) => /^\/api\/projects\/[^/]+\/flow-ext$/.test(p),
  (m, p) => p === '/api/settings' && m !== 'GET',
  (m, p) => p.endsWith('/open-folder'),
  (m, p) => p.startsWith('/api/tiktok/login') || p.startsWith('/api/tiktok/logout'),
  (m, p) => /^\/api\/projects\/[^/]+\/agent$/.test(p) && m !== 'GET',
]

function check(method, path) {
  if (typeof path !== 'string' || !path.startsWith('/api/') || path.includes('..')) {
    return 'path phải bắt đầu bằng /api/'
  }
  const bare = path.split('?')[0]
  if (ALWAYS_BLOCKED.some((r) => r.test(bare))) return 'Trợ lý không được gọi đường dẫn này.'
  if (RESTRICTED && REMOTE_BLOCKED.some((f) => f(method, bare))) {
    return 'Việc này chỉ làm được trên máy chủ (thiết bị đang chat chỉ có quyền PIN).'
  }
  return null
}

function clip(s) {
  return s.length <= MAX_TEXT ? s : s.slice(0, MAX_TEXT) + `\n…(cắt bớt, tổng ${s.length} ký tự)`
}

// Secrets never reach the model, and a settings save only changes the fields it names (the rest, secrets included,
// comes from the server copy), so a partial body cannot wipe anything.
const SECRETS = ['tiktokClientSecret', 'lanPin', 'telegramBotToken', 'syncDbUrl']

async function callApi({ method = 'GET', path, body }) {
  method = String(method).toUpperCase()
  const bad = check(method, path)
  if (bad) return { error: bad }
  const bare = path.split('?')[0]
  if (bare === '/api/settings') {
    const cur = await (await fetch(APP + '/api/settings')).json()
    if (method === 'PUT') {
      const patch = { ...(body || {}) }
      SECRETS.forEach((k) => delete patch[k])
      body = { ...cur, ...patch }
    } else if (method === 'GET') {
      SECRETS.forEach((k) => { if (cur[k]) cur[k] = '(ẩn)' })
      return { status: 200, text: JSON.stringify(cur) }
    }
  }
  if (method === 'GET' && bare === '/api/projects') {
    // The full list carries every scene of every project: far too big. Give a summary, newest first.
    const r = await fetch(APP + '/api/projects')
    if (!r.ok) return { status: r.status, text: await r.text() }
    const all = (await r.json()).map((p) => ({
      id: p.id, name: p.name, niche: p.niche, aspect: p.aspect, createdAt: p.createdAt, updatedAt: p.updatedAt,
      scenes: p.scenes?.length || 0,
      withMedia: (p.scenes || []).filter((s) => s.mediaFile).length,
      withVoice: (p.scenes || []).filter((s) => s.audioFile).length,
      lastRender: p.lastRender || null, slides: p.slideFiles?.length || 0,
      queueState: p.queueState || null, scheduledAt: p.scheduledAt || null, queueError: p.queueError || null,
    })).sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
    return { status: 200, text: JSON.stringify({ count: all.length, projects: all }) }
  }
  const opts = { method, headers: {} }
  if (body !== undefined && method !== 'GET' && method !== 'DELETE') {
    opts.headers['Content-Type'] = 'application/json'
    opts.body = JSON.stringify(body)
  } else if (method === 'POST') {
    opts.headers['Content-Type'] = 'application/json'
    opts.body = '{}'
  }
  const res = await fetch(APP + path, opts)
  const text = await res.text()
  return { status: res.status, text }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitJob({ jobId, maxSeconds = 300, tail = 25 }) {
  const until = Date.now() + Math.min(Math.max(5, maxSeconds), 1500) * 1000
  let job
  for (;;) {
    const r = await fetch(`${APP}/api/jobs/${encodeURIComponent(jobId)}`)
    if (!r.ok) return { error: `Không tìm thấy job ${jobId} (HTTP ${r.status})` }
    job = await r.json()
    if (job.status !== 'RUNNING' || Date.now() > until) break
    await sleep(3000)
  }
  let log = []
  try {
    const r = await fetch(`${APP}/api/jobs/${encodeURIComponent(jobId)}/log?tail=${tail}`)
    if (r.ok) log = await r.json()
  } catch { /* ignore */ }
  return { id: job.id, type: job.type, title: job.title, status: job.status, error: job.error, log }
}

async function viewImage({ projectId, folder, name }) {
  if (!['assets', 'output'].includes(folder) || /[\\/]/.test(name) || /[\\/]/.test(projectId)) {
    return { content: [{ type: 'text', text: 'Tên file không hợp lệ' }], isError: true }
  }
  const r = await fetch(`${APP}/api/projects/${encodeURIComponent(projectId)}/files/${folder}/${encodeURIComponent(name)}`)
  if (!r.ok) return { content: [{ type: 'text', text: `Không mở được hình (HTTP ${r.status})` }], isError: true }
  const type = (r.headers.get('content-type') || '').split(';')[0]
  if (!/^image\/(png|jpeg|webp|gif)$/.test(type)) {
    return { content: [{ type: 'text', text: `File không phải hình (${type || 'không rõ'})` }], isError: true }
  }
  const buf = Buffer.from(await r.arrayBuffer())
  if (buf.length > 3_700_000) {
    return { content: [{ type: 'text', text: `Hình quá lớn để xem (${Math.round(buf.length / 1e6)} MB)` }], isError: true }
  }
  return { content: [{ type: 'image', data: buf.toString('base64'), mimeType: type }] }
}

async function callTool(name, args = {}) {
  try {
    if (name === 'api') {
      const r = await callApi(args)
      if (r.error) return { content: [{ type: 'text', text: r.error }], isError: true }
      const ok = r.status >= 200 && r.status < 300
      return { content: [{ type: 'text', text: clip(`HTTP ${r.status}\n${r.text}`) }], isError: !ok }
    }
    if (name === 'wait_job') {
      const r = await waitJob(args)
      return { content: [{ type: 'text', text: clip(JSON.stringify(r, null, 1)) }], isError: !!r.error }
    }
    if (name === 'view_image') return await viewImage(args)
    if (name === 'send_file') {
      const r = await fetch(`${APP}/api/telegram/send`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args),
      })
      const t = await r.text()
      return { content: [{ type: 'text', text: `HTTP ${r.status}\n${t}` }], isError: !r.ok }
    }
    return { content: [{ type: 'text', text: `Không có công cụ ${name}` }], isError: true }
  } catch (e) {
    return { content: [{ type: 'text', text: `Lỗi khi gọi app: ${e.message}` }], isError: true }
  }
}

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n')
}

async function handle(msg) {
  const { id, method, params } = msg
  if (method === 'initialize') {
    return send({
      jsonrpc: '2.0', id,
      result: {
        protocolVersion: params?.protocolVersion || '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'media-studio', version: '1.0.0' },
      },
    })
  }
  if (method === 'tools/list') return send({ jsonrpc: '2.0', id, result: { tools: TOOLS } })
  if (method === 'tools/call') {
    const result = await callTool(params?.name, params?.arguments || {})
    return send({ jsonrpc: '2.0', id, result })
  }
  if (method === 'ping') return send({ jsonrpc: '2.0', id, result: {} })
  if (id !== undefined && id !== null) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } })
  }
  // notifications (initialized, cancelled…) need no answer
}

let buf = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buf += chunk
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim()
    buf = buf.slice(i + 1)
    if (!line) continue
    let msg
    try { msg = JSON.parse(line) } catch { continue }
    handle(msg).catch((e) => {
      if (msg.id !== undefined) send({ jsonrpc: '2.0', id: msg.id, error: { code: -32603, message: e.message } })
    })
  }
})
process.stdin.on('end', () => process.exit(0))
