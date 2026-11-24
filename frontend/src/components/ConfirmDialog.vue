<script setup>
import { ref, watch, nextTick } from 'vue'
import { dialog, answer } from '../confirm'

// macOS-style alert: icon, short bold title, one explanatory line, Cancel + action. Esc cancels, Enter runs the
// focused button. A destructive action is red and Cancel is the default (so Enter never deletes by accident).
const box = ref(null)
const cancelBtn = ref(null)
const okBtn = ref(null)
let lastFocus = null

watch(() => dialog.open, async (open) => {
  if (open) {
    lastFocus = document.activeElement
    await nextTick()
    ;(dialog.danger ? cancelBtn.value : okBtn.value)?.focus()
  } else {
    lastFocus?.focus?.()
    lastFocus = null
  }
})

function onKey(e) {
  if (e.key === 'Escape') {
    e.preventDefault()
    answer(false)
  } else if (e.key === 'Tab') {
    // keep focus inside the dialog (two buttons)
    e.preventDefault()
    ;(document.activeElement === okBtn.value ? cancelBtn.value : okBtn.value)?.focus()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="cd">
      <div v-if="dialog.open" class="cd-back" @mousedown.self="answer(false)" @keydown="onKey">
        <div ref="box" class="cd" role="alertdialog" aria-modal="true" aria-labelledby="cd-title"
             :aria-describedby="dialog.message ? 'cd-msg' : undefined">
          <div class="cd-ico" :class="{ danger: dialog.danger }" aria-hidden="true">
            <Icon :name="dialog.icon || (dialog.danger ? 'trash' : 'alert')" :size="26" />
          </div>
          <h2 id="cd-title">{{ dialog.title }}</h2>
          <p v-if="dialog.message" id="cd-msg">{{ dialog.message }}</p>
          <div class="cd-actions">
            <button ref="cancelBtn" type="button" @click="answer(false)">{{ dialog.cancelLabel }}</button>
            <button ref="okBtn" type="button" :class="dialog.danger ? 'destructive' : 'primary'" @click="answer(true)">
              {{ dialog.confirmLabel }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.cd-back { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 16px;
  background: rgba(0, 0, 0, .28); -webkit-backdrop-filter: blur(3px); backdrop-filter: blur(3px); }
.cd { width: min(300px, 100%); padding: 22px 18px 16px; border-radius: 14px; text-align: center;
  background: color-mix(in srgb, var(--panel) 92%, transparent); border: 1px solid var(--border);
  -webkit-backdrop-filter: var(--material-blur); backdrop-filter: var(--material-blur); box-shadow: var(--shadow-3); }
.cd-ico { width: 56px; height: 56px; margin: 0 auto 12px; border-radius: 14px; display: grid; place-items: center; color: #fff;
  background: linear-gradient(180deg, #FFC24A, #F59A0B); box-shadow: 0 2px 6px rgba(0, 0, 0, .18), inset 0 1px 0 rgba(255, 255, 255, .35); }
.cd-ico.danger { background: linear-gradient(180deg, #FF6B60, #E0352B); }
.cd-ico :deep(.ico) { color: #fff; margin: 0; }
h2 { margin: 0 0 6px; font-size: 15px; font-weight: 700; line-height: 1.3; letter-spacing: -.01em; overflow-wrap: anywhere; }
p { margin: 0; font-size: 13px; line-height: 1.45; color: var(--muted); overflow-wrap: anywhere; white-space: pre-line; }
.cd-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px; margin-top: 18px; }
.cd-actions button { min-height: 34px; border-radius: 8px; font-weight: 500; background: var(--fill-strong); border-color: transparent; box-shadow: none; }
.cd-actions button:hover:not(:disabled) { background: color-mix(in srgb, var(--fill-strong) 70%, var(--text) 12%); }
.cd-actions button.primary { background: var(--accent); color: var(--on-accent); font-weight: 600; }
.cd-actions button.primary:hover:not(:disabled) { background: var(--accent-2); }
.cd-actions button.destructive { background: #D92D20; color: #fff; font-weight: 600; }
.cd-actions button.destructive:hover:not(:disabled) { background: #B8231A; }

.cd-enter-active { transition: opacity 180ms ease-out; }
.cd-leave-active { transition: opacity 120ms ease-in; }
.cd-enter-active .cd { transition: transform 220ms cubic-bezier(.2, .9, .3, 1.15); }
.cd-leave-active .cd { transition: transform 120ms ease-in; }
.cd-enter-from, .cd-leave-to { opacity: 0; }
.cd-enter-from .cd { transform: scale(1.08); }
.cd-leave-to .cd { transform: scale(.97); }
@media (max-width: 820px) { .cd-actions button { min-height: 44px; } }
</style>
