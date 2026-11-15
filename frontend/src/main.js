import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import ProjectsView from './views/ProjectsView.vue'
import ProjectView from './views/ProjectView.vue'
import SettingsView from './views/SettingsView.vue'
import Icon from './components/Icon.vue'
import './style.css'

// Theme chosen in the sidebar (light / dark / follow the system); applied before the first paint.
try {
  const t = localStorage.getItem('theme')
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t
} catch { /* storage blocked: follow the system */ }

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: ProjectsView },
    { path: '/project/:id', component: ProjectView, props: true },
    { path: '/settings', component: SettingsView }
  ]
})

// Move focus to the page content after each route change (keyboard + screen-reader users).
router.afterEach(() => setTimeout(() => document.getElementById('main')?.focus({ preventScroll: true }), 0))

createApp(App).use(router).component('Icon', Icon).mount('#app')
