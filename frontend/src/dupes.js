import { api } from './api'
import { ask } from './confirm'

/** Labels of the post states shared between computers (see PostSync.state on the backend). */
export const SYNC_STATE = {
  QUEUED: 'Chờ chuẩn bị', PREPARING: 'Đang chuẩn bị', READY: 'Sẵn sàng', POSTING: 'Đang đăng',
  POSTED: 'Đã đăng', DRAFTED: 'Đã lưu nháp', FAILED: 'Lỗi', NONE: 'Ngoài danh sách',
}

const day = (iso) => new Date(iso).toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })

function describe(m) {
  let s = `"${m.name}" ${m.thisMachine ? 'trên máy này' : 'trên máy ' + m.machine}: ${SYNC_STATE[m.state] || m.state}`
  if (m.postedAt) s += ' ' + day(m.postedAt)
  else if (m.scheduledAt && m.state !== 'NONE') s += ', hẹn ' + day(m.scheduledAt)
  if (m.deleted) s += ' (dự án đã xoá)'
  return s
}

/**
 * Warns when topics look already made, on this computer or another one sharing the posts list.
 * Resolves true to go on (no duplicates, the check failed, or the user chose to create anyway).
 */
export async function confirmNoDuplicates(topics) {
  const list = topics.map((t) => t.trim()).filter(Boolean)
  if (!list.length) return true
  let found
  try {
    found = await api.post('/api/sync/check', { topics: list })
  } catch {
    return true // never block creating because of the check
  }
  const keys = Object.keys(found || {})
  if (!keys.length) return true
  const lines = keys.slice(0, 6).map((t) => {
    const ms = found[t]
    return `• ${t}\n   ↳ ${ms.slice(0, 2).map(describe).join('\n   ↳ ')}${ms.length > 2 ? `\n   ↳ … và ${ms.length - 2} bài khác` : ''}`
  })
  if (keys.length > 6) lines.push(`… và ${keys.length - 6} chủ đề khác`)
  return ask({
    title: keys.length === 1 ? 'Chủ đề này có vẻ đã làm rồi' : `${keys.length} chủ đề có vẻ đã làm rồi`,
    message: lines.join('\n') + '\n\nVẫn tạo?',
    confirmLabel: 'Vẫn tạo',
    cancelLabel: 'Quay lại sửa',
    icon: 'alert',
  })
}
