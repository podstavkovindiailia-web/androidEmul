<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { H264Decoder, isWebCodecsSupported } from '../lib/decoder'
import { StreamClient, type ClientMessage, type ServerMessage } from '../lib/stream'
import { Keycode, translateKeyDown, translateKeyUp } from '../lib/keymap'

const props = defineProps<{ serial: string }>()

const ACTION_DOWN = 0
const ACTION_UP = 1
const ACTION_MOVE = 2

const viewport = ref<HTMLDivElement>()
const canvas = ref<HTMLCanvasElement>()
const state = reactive({
  status: 'connecting' as 'connecting' | 'starting' | 'streaming' | 'closed',
  message: '',
  deviceName: '',
  input: '',
  width: 0,
  height: 0,
  fps: 0,
  rtt: 0,
  resyncs: 0,
  displayWidth: 0,
  displayHeight: 0,
})
const showStats = ref(true)
const supported = isWebCodecsSupported()

let client: StreamClient | null = null
let decoder: H264Decoder | null = null
let ctx: CanvasRenderingContext2D | null = null
let frames = 0
let statsTimer: number | undefined
let resizeObserver: ResizeObserver | null = null
let reconnectTimer: number | undefined

// ---------- video ----------

function onFrame(frame: VideoFrame) {
  const c = canvas.value
  if (!c || !ctx) {
    frame.close()
    return
  }
  const w = frame.displayWidth
  const h = frame.displayHeight
  if (c.width !== w || c.height !== h) {
    c.width = w
    c.height = h
    state.width = w
    state.height = h
    layout()
  }
  // Draw immediately: no frame queue, the newest frame is always what is on screen.
  ctx.drawImage(frame, 0, 0, w, h)
  frame.close()
  frames++
  if (state.status !== 'streaming') state.status = 'streaming'
}

function send(msg: ClientMessage) {
  client?.send(msg)
}

function start() {
  stop()
  state.status = 'connecting'
  state.message = ''
  decoder = new H264Decoder({
    onFrame,
    onNeedKeyFrame: () => {
      state.resyncs++
      send({ type: 'command', name: 'resetVideo' })
    },
    onError: (m) => console.warn('decoder:', m),
  })
  client = new StreamClient({
    onMessage: (msg: ServerMessage) => {
      switch (msg.type) {
        case 'starting':
          state.status = 'starting'
          break
        case 'started':
          state.deviceName = msg.deviceName
          state.input = msg.input ?? ''
          if (!state.width) {
            state.width = msg.width
            state.height = msg.height
          }
          break
        case 'closed':
          state.status = 'closed'
          state.message = msg.reason
          break
      }
    },
    onPacket: (flags, pts, data) => decoder?.push(flags, pts, data),
    onDisconnect: () => {
      if (state.status !== 'closed') {
        state.status = 'closed'
        state.message = 'Соединение потеряно, переподключение…'
        reconnectTimer = window.setTimeout(start, 1500)
      }
    },
  })
  client.connect(props.serial)
}

function stop() {
  window.clearTimeout(reconnectTimer)
  client?.close()
  client = null
  decoder?.close()
  decoder = null
  pointers.clear()
}

// ---------- layout: fit the video into the viewport keeping aspect ratio ----------

function layout() {
  const vp = viewport.value
  if (!vp || !state.width || !state.height) return
  const pad = 16 // room for the device frame
  const scale = Math.min((vp.clientWidth - pad) / state.width, (vp.clientHeight - pad) / state.height)
  state.displayWidth = Math.floor(state.width * scale)
  state.displayHeight = Math.floor(state.height * scale)
}

const canvasStyle = computed(() =>
  state.displayWidth ? { width: `${state.displayWidth}px`, height: `${state.displayHeight}px` } : {},
)

// ---------- touch / mouse input ----------

/** browser pointerId -> small Android pointer id */
const pointers = new Map<number, number>()

function allocPointerId(browserId: number): number {
  const used = new Set(pointers.values())
  let id = 0
  while (used.has(id)) id++
  pointers.set(browserId, id)
  return id
}

function toVideoCoords(e: PointerEvent | WheelEvent) {
  const rect = canvas.value!.getBoundingClientRect()
  const w = state.width
  const h = state.height
  const x = Math.round(((e.clientX - rect.left) / rect.width) * w)
  const y = Math.round(((e.clientY - rect.top) / rect.height) * h)
  return { x: Math.min(Math.max(x, 0), w - 1), y: Math.min(Math.max(y, 0), h - 1), width: w, height: h }
}

function sendTouch(action: number, e: PointerEvent, androidId: number) {
  if (!state.width) return
  const pressure = e.pointerType === 'mouse' ? 1 : e.pressure || 1
  send({ type: 'touch', action, pointerId: androidId, pressure, ...toVideoCoords(e) })
}

function onPointerDown(e: PointerEvent) {
  viewport.value?.focus()
  if (e.pointerType === 'mouse' && e.button !== 0) {
    // shortcuts: right click = BACK, middle click = HOME
    if (e.button === 2) {
      send({ type: 'backOrScreenOn', action: ACTION_DOWN })
      send({ type: 'backOrScreenOn', action: ACTION_UP })
    } else if (e.button === 1) {
      pressKey(Keycode.HOME)
    }
    e.preventDefault()
    return
  }
  canvas.value!.setPointerCapture(e.pointerId)
  sendTouch(ACTION_DOWN, e, allocPointerId(e.pointerId))
  e.preventDefault()
}

function onPointerMove(e: PointerEvent) {
  const id = pointers.get(e.pointerId)
  if (id === undefined) return
  // coalesced events give the full-resolution path of fast swipes
  const events = typeof e.getCoalescedEvents === 'function' ? e.getCoalescedEvents() : []
  for (const ev of events.length ? events : [e]) sendTouch(ACTION_MOVE, ev, id)
}

function onPointerUp(e: PointerEvent) {
  const id = pointers.get(e.pointerId)
  if (id === undefined) return
  pointers.delete(e.pointerId)
  sendTouch(ACTION_UP, e, id)
}

function onWheel(e: WheelEvent) {
  if (!state.width) return
  e.preventDefault()
  const scale = e.deltaMode === WheelEvent.DOM_DELTA_PIXEL ? 1 / 100 : 1 / 3
  const clamp = (v: number) => Math.max(-1, Math.min(1, v))
  send({ type: 'scroll', ...toVideoCoords(e), hScroll: clamp(-e.deltaX * scale), vScroll: clamp(-e.deltaY * scale) })
}

// ---------- keyboard ----------

function pressKey(keycode: number) {
  send({ type: 'key', action: ACTION_DOWN, keycode })
  send({ type: 'key', action: ACTION_UP, keycode })
}

function onKeyDown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyV') return // handled by paste event
  const action = translateKeyDown(e)
  if (!action) return
  e.preventDefault()
  if (action.kind === 'text') send({ type: 'text', text: action.text })
  else send({ type: 'key', action: ACTION_DOWN, keycode: action.keycode, metaState: action.metaState, repeat: e.repeat ? 1 : 0 })
}

function onKeyUp(e: KeyboardEvent) {
  const action = translateKeyUp(e)
  if (action?.kind === 'key') {
    e.preventDefault()
    send({ type: 'key', action: ACTION_UP, keycode: action.keycode, metaState: action.metaState })
  }
}

function onPaste(e: ClipboardEvent) {
  const text = e.clipboardData?.getData('text')
  if (text) {
    e.preventDefault()
    send({ type: 'text', text })
  }
}

// ---------- toolbar ----------

function command(name: string) {
  send({ type: 'command', name })
}

function screenshot() {
  canvas.value?.toBlob((blob) => {
    if (!blob) return
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${props.serial}-${new Date().toISOString().replace(/[:.]/g, '-')}.png`
    a.click()
    URL.revokeObjectURL(a.href)
  })
}

// ---------- lifecycle ----------

onMounted(() => {
  ctx = canvas.value!.getContext('2d', { alpha: false, desynchronized: true }) as CanvasRenderingContext2D
  resizeObserver = new ResizeObserver(layout)
  resizeObserver.observe(viewport.value!)
  statsTimer = window.setInterval(() => {
    state.fps = frames
    frames = 0
    state.rtt = Math.round(client?.rtt ?? 0)
  }, 1000)
  if (supported) start()
  viewport.value?.focus()
})

onUnmounted(() => {
  stop()
  window.clearInterval(statsTimer)
  resizeObserver?.disconnect()
})

const statusText = computed(() => {
  switch (state.status) {
    case 'connecting':
      return 'Подключение…'
    case 'starting':
      return 'Запуск трансляции на устройстве…'
    case 'closed':
      return state.message || 'Трансляция остановлена'
    default:
      return ''
  }
})
</script>

<template>
  <section class="screen">
    <header class="topbar">
      <div class="title">
        <strong>{{ state.deviceName || serial }}</strong>
        <span class="muted">{{ serial }}</span>
      </div>
      <div class="actions">
        <button title="Повернуть" @click="command('rotate')">⟲ Поворот</button>
        <button title="Шторка уведомлений" @click="command('expandNotifications')">▼ Уведомления</button>
        <button title="Скриншот" @click="screenshot">📷 Скриншот</button>
        <button title="Статистика" :class="{ on: showStats }" @click="showStats = !showStats">📈</button>
      </div>
    </header>

    <div class="body">
      <div
        ref="viewport"
        class="viewport"
        tabindex="0"
        @keydown="onKeyDown"
        @keyup="onKeyUp"
        @paste="onPaste"
        @contextmenu.prevent
      >
        <div v-if="!supported" class="overlay error">
          Браузер не поддерживает WebCodecs (VideoDecoder).<br />
          Используйте свежий Chrome / Edge / Safari / Firefox и откройте страницу по HTTPS или через localhost.
        </div>
        <canvas
          ref="canvas"
          class="canvas"
          :class="{ hidden: !state.width }"
          :style="canvasStyle"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @wheel="onWheel"
        />
        <div v-if="supported && statusText" class="overlay" :class="{ error: state.status === 'closed' }">
          <div v-if="state.status !== 'closed'" class="spinner" />
          <pre>{{ statusText }}</pre>
          <button v-if="state.status === 'closed'" @click="start">Переподключиться</button>
        </div>
        <div v-if="showStats && state.status === 'streaming'" class="stats">
          {{ state.width }}×{{ state.height }} · {{ state.fps }} fps · RTT {{ state.rtt }} ms
          <template v-if="state.input"> · ввод: {{ state.input }}</template>
          <template v-if="state.resyncs"> · resync {{ state.resyncs }}</template>
        </div>
      </div>

      <nav class="side-buttons">
        <button title="Питание" @click="pressKey(Keycode.POWER)">⏻</button>
        <button title="Громкость +" @click="pressKey(Keycode.VOLUME_UP)">🔊</button>
        <button title="Громкость −" @click="pressKey(Keycode.VOLUME_DOWN)">🔉</button>
      </nav>
    </div>

    <footer class="navbar">
      <button title="Назад" @click="pressKey(Keycode.BACK)">
        <svg viewBox="0 0 24 24"><path d="M17 4 6 12l11 8z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" /></svg>
      </button>
      <button title="Домой" @click="pressKey(Keycode.HOME)">
        <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7" fill="none" stroke="currentColor" stroke-width="2" /></svg>
      </button>
      <button title="Недавние" @click="pressKey(Keycode.APP_SWITCH)">
        <svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1.5" fill="none" stroke="currentColor" stroke-width="2" /></svg>
      </button>
    </footer>
    <p class="hint muted">
      Мышь/тач — касания (мультитач поддерживается) · колесо — прокрутка · ПКМ — «Назад» · СКМ — «Домой» · клавиатура и Ctrl+V работают при фокусе на экране
    </p>
  </section>
</template>

<style scoped>
.screen { flex: 1; display: flex; flex-direction: column; min-width: 0; padding: 12px 16px; gap: 10px; }
.topbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { display: flex; flex-direction: column; }
.actions { display: flex; gap: 6px; flex-wrap: wrap; }
.actions button.on { border-color: var(--accent); }
.body { flex: 1; display: flex; gap: 10px; min-height: 0; }
.viewport {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 0;
  min-width: 0;
  outline: none;
  overflow: hidden;
}
.canvas {
  display: block;
  background: #000;
  border-radius: 14px;
  box-shadow: 0 0 0 6px #22262f, 0 12px 40px rgba(0, 0, 0, .5);
  touch-action: none;
  user-select: none;
  cursor: pointer;
}
.viewport:focus .canvas { box-shadow: 0 0 0 6px #2c3a33, 0 12px 40px rgba(0, 0, 0, .5); }
.canvas.hidden { visibility: hidden; }
.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
  color: var(--muted);
  pointer-events: none;
}
.overlay button { pointer-events: auto; }
.overlay pre { white-space: pre-wrap; font-family: inherit; max-width: 640px; margin: 0; }
.overlay.error { color: var(--danger); }
.spinner { width: 32px; height: 32px; border: 3px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.stats {
  position: absolute;
  top: 8px;
  left: 8px;
  font: 12px ui-monospace, monospace;
  background: rgba(0, 0, 0, .6);
  padding: 4px 8px;
  border-radius: 6px;
  pointer-events: none;
}
.side-buttons { display: flex; flex-direction: column; gap: 8px; justify-content: center; }
.side-buttons button { width: 44px; height: 44px; font-size: 18px; }
.navbar { display: flex; justify-content: center; gap: 40px; }
.navbar button { width: 56px; height: 40px; display: flex; align-items: center; justify-content: center; }
.navbar svg { width: 22px; height: 22px; }
.hint { text-align: center; font-size: 12px; margin: 0; }
.muted { color: var(--muted); font-size: 12px; }
</style>
