<script setup>
import { ref } from 'vue'
import ConfirmDialog from './components/ConfirmDialog.vue'

// light | dark | system — remembered per browser only (a convenience, never required).
const themes = [
  { id: 'system', icon: 'monitor', label: 'Theo hệ thống' },
  { id: 'light', icon: 'sun', label: 'Sáng' },
  { id: 'dark', icon: 'moon', label: 'Tối' },
]
const theme = ref(document.documentElement.dataset.theme || 'system')
function setTheme(t) {
  theme.value = t
  if (t === 'system') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = t
  try { localStorage.setItem('theme', t) } catch { /* ignore */ }
}
// href="#main" would change the hash route, so move focus by hand.
const focusMain = () => document.getElementById('main')?.focus()
</script>

<template>
  <a class="skip" href="#main" @click.prevent="focusMain">Bỏ qua tới nội dung</a>
  <div class="shell">
    <aside class="nav" aria-label="Điều hướng chính">
      <div class="brand">
        <span class="logo"><Icon name="clapper" :size="18" /></span>
        <span>Media Studio</span>
      </div>
      <nav class="links">
        <div class="group">Thư viện</div>
        <RouterLink to="/" class="link" exact-active-class="active"><Icon name="grid" /> <span>Dự án</span></RouterLink>
        <RouterLink to="/settings" class="link" active-class="active"><Icon name="settings" /> <span>Cài đặt</span></RouterLink>
      </nav>
      <div class="foot">
        <div class="theme" role="radiogroup" aria-label="Giao diện">
          <button v-for="t in themes" :key="t.id" class="small icon-only" role="radio" :aria-checked="theme === t.id"
                  :class="{ on: theme === t.id }" :title="t.label" :aria-label="t.label" @click="setTheme(t.id)">
            <Icon :name="t.icon" />
          </button>
        </div>
        <div class="stack muted">Claude · Google Flow · Gemini · Canva · VieNeu-TTS · FFmpeg</div>
      </div>
    </aside>
    <main id="main" class="main" tabindex="-1">
      <div class="wrap"><RouterView :key="$route.fullPath" /></div>
    </main>
  </div>
  <ConfirmDialog />
</template>

<style scoped>
.skip { position: absolute; left: 8px; top: -48px; z-index: 100; background: var(--accent); color: var(--on-accent);
  padding: 8px 12px; border-radius: var(--r-sm); }
.skip:focus { top: 8px; }
.shell { display: flex; height: 100%; }
/* macOS source list: a translucent material, small grey group label, rows with a rounded grey selection. */
.nav {
  width: 216px; flex-shrink: 0; background: var(--sidebar); border-right: 1px solid var(--border);
  -webkit-backdrop-filter: var(--material-blur); backdrop-filter: var(--material-blur);
  padding: var(--s4) 10px; display: flex; flex-direction: column; gap: var(--s1);
}
.brand { display: flex; align-items: center; gap: 9px; font-weight: 600; font-size: 14px; letter-spacing: -.01em; padding: 2px 8px var(--s4); }
.logo { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; color: #fff;
  background: linear-gradient(180deg, #3D9BFF, #0A63D8); box-shadow: 0 1px 2px rgba(0, 0, 0, .2), inset 0 1px 0 rgba(255, 255, 255, .3); }
.links { display: flex; flex-direction: column; gap: 1px; }
.group { font-size: 11px; font-weight: 600; color: var(--muted); padding: 4px 8px 4px; }
.link { display: flex; align-items: center; gap: 8px; color: var(--text); padding: 5px 8px; min-height: 30px; border-radius: var(--r-sm);
  font-weight: 400; transition: background-color var(--t-fast) var(--ease-out); }
.link :deep(.ico) { color: var(--accent); }
.link:hover { background: var(--fill); text-decoration: none; }
.link.active { background: var(--fill-strong); font-weight: 500; }
.foot { margin-top: auto; display: flex; flex-direction: column; gap: var(--s3); padding: var(--s2); }
/* Segmented control */
.theme { display: flex; gap: 2px; padding: 2px; border-radius: 8px; background: var(--fill); width: max-content; }
.theme button { border-color: transparent; box-shadow: none; background: transparent; color: var(--muted); border-radius: 6px; }
.theme button:hover:not(:disabled) { background: transparent; color: var(--text); }
.theme button.on { background: var(--panel); color: var(--text); box-shadow: 0 1px 2px rgba(0, 0, 0, .14), 0 0 0 .5px rgba(0, 0, 0, .06); }
.stack { font-size: 11px; line-height: 1.5; }
.main { flex: 1; overflow: auto; min-width: 0; outline: none; }
.wrap { max-width: 1440px; margin: 0 auto; padding: var(--s5) var(--s6) var(--s7); }
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) { .nav { background: var(--panel-2); } }

/* Phone: the side menu becomes a bar on top. */
@media (max-width: 820px) {
  .shell { flex-direction: column; height: 100dvh; }
  .nav {
    width: auto; flex-direction: row; align-items: center; gap: var(--s1);
    border-right: 0; border-bottom: 1px solid var(--border);
    
    padding: calc(8px + env(safe-area-inset-top)) calc(10px + env(safe-area-inset-right)) 8px calc(10px + env(safe-area-inset-left));
  }
  .brand { padding: 0; font-size: 15px; margin-right: auto; }
  .brand span:last-child { display: none; }
  .links { flex-direction: row; }
  .group { display: none; }
  .link { padding: 10px 12px; min-height: 44px; }
  .foot { margin: 0; padding: 0; }
  .stack { display: none; }
  .wrap { padding: var(--s3) calc(12px + env(safe-area-inset-right)) calc(24px + env(safe-area-inset-bottom)) calc(12px + env(safe-area-inset-left)); }
}
@media (max-width: 420px) {
  .link span { display: none; }
  .theme button:not(.on) { display: none; }
}
</style>
