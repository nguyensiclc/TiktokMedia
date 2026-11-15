<script setup>
import { ref } from 'vue'

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
</template>

<style scoped>
.skip { position: absolute; left: 8px; top: -48px; z-index: 100; background: var(--accent); color: var(--on-accent);
  padding: 8px 12px; border-radius: var(--r-sm); }
.skip:focus { top: 8px; }
.shell { display: flex; height: 100%; }
.nav {
  width: 224px; flex-shrink: 0; background: var(--panel); border-right: 1px solid var(--border);
  padding: var(--s4) var(--s3); display: flex; flex-direction: column; gap: var(--s1);
}
.brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; padding: var(--s1) var(--s2) var(--s5); }
.logo { display: grid; place-items: center; width: 32px; height: 32px; border-radius: var(--r-sm);
  background: var(--accent); color: var(--on-accent); }
.links { display: flex; flex-direction: column; gap: 2px; }
.link { display: flex; align-items: center; gap: 10px; color: var(--muted); padding: 9px 12px; border-radius: var(--r-sm);
  font-weight: 500; transition: background-color var(--t-fast) var(--ease-out), color var(--t-fast) var(--ease-out); }
.link:hover { background: var(--panel-2); color: var(--text); text-decoration: none; }
.link.active { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.foot { margin-top: auto; display: flex; flex-direction: column; gap: var(--s3); padding: var(--s2); }
.theme { display: flex; gap: 4px; padding: 3px; border-radius: var(--r-sm); background: var(--panel-2); width: max-content; }
.theme button { border-color: transparent; box-shadow: none; background: transparent; color: var(--muted); }
.theme button.on { background: var(--panel); color: var(--accent); border-color: var(--border); box-shadow: var(--shadow-1); }
.stack { font-size: 12px; line-height: 1.5; }
.main { flex: 1; overflow: auto; min-width: 0; outline: none; }
.wrap { max-width: 1440px; margin: 0 auto; padding: var(--s5) var(--s6) var(--s7); }

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
