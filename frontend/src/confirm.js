import { reactive } from 'vue'

/** State of the one app-wide confirmation dialog (rendered by ConfirmDialog.vue in App.vue). */
export const dialog = reactive({ open: false, title: '', message: '', confirmLabel: 'OK', cancelLabel: 'Huỷ', danger: false, icon: '' })
let resolver = null

/**
 * Asks the user to confirm; replaces window.confirm(). Resolves true (confirmed) or false (cancelled / Esc).
 * @param {{title: string, message?: string, confirmLabel?: string, cancelLabel?: string, danger?: boolean, icon?: string}} o
 */
export function ask(o) {
  if (resolver) resolver(false) // a second question replaces the first
  Object.assign(dialog, { message: '', confirmLabel: 'OK', cancelLabel: 'Huỷ', danger: false, icon: '' }, o, { open: true })
  return new Promise((resolve) => { resolver = resolve })
}

export function answer(ok) {
  dialog.open = false
  const r = resolver
  resolver = null
  r?.(ok)
}
