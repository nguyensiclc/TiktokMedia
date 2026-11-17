<script setup>
// One icon family (lucide, 2px stroke) for the whole app instead of emoji. Decorative: hidden from screen readers,
// so every icon-only button must carry its own aria-label / title.
import {
  AlertTriangle, ArrowLeft, Check, ChevronRight, Clapperboard, Copy, FileText, Film, Flag, FlaskConical, Folder, FolderOpen, Image,
  Inbox, KeyRound, LayoutGrid, Link, Loader2, MapPin, Mic, Monitor, Moon, Music, Palette, Pause, Play, Plus, Rocket, Send,
  Settings, SlidersHorizontal, Smartphone, Sparkles, Square, Star, Sun, Trash2, Wand2, X, XCircle, Zap, ZoomIn,
} from 'lucide-vue-next'

const map = {
  alert: AlertTriangle, back: ArrowLeft, check: Check, chevron: ChevronRight, clapper: Clapperboard, copy: Copy, file: FileText,
  film: Film, flag: Flag, flask: FlaskConical, folder: Folder, 'folder-open': FolderOpen, image: Image, inbox: Inbox, key: KeyRound,
  grid: LayoutGrid, link: Link, loader: Loader2, pin: MapPin, mic: Mic, monitor: Monitor, moon: Moon, music: Music,
  palette: Palette, pause: Pause, play: Play, plus: Plus, rocket: Rocket, send: Send, settings: Settings, sliders: SlidersHorizontal,
  phone: Smartphone, sparkles: Sparkles, stop: Square, star: Star, sun: Sun, trash: Trash2, flow: Wand2, x: X, fail: XCircle, zap: Zap, zoom: ZoomIn,
}

const props = defineProps({ name: { type: String, required: true }, size: { type: Number, default: 16 } })
</script>

<template>
  <component :is="map[props.name]" v-if="map[props.name]" :size="props.size" :stroke-width="2"
             class="ico" :class="{ spin: props.name === 'loader' }" aria-hidden="true" focusable="false" />
</template>

<style scoped>
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
