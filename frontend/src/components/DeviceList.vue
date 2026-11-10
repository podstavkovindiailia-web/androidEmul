<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, type Device } from '../lib/api'

defineProps<{ selected: string | null }>()
const emit = defineEmits<{ select: [serial: string] }>()

const devices = ref<Device[]>([])
const error = ref<string | null>(null)
const address = ref('')
const connectResult = ref('')
let timer: number | undefined

async function refresh() {
  try {
    devices.value = await api.devices()
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function connect() {
  if (!address.value) return
  try {
    connectResult.value = (await api.connect(address.value)).result
    await refresh()
  } catch (e) {
    connectResult.value = (e as Error).message
  }
}

onMounted(() => {
  refresh()
  timer = window.setInterval(refresh, 3000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <aside class="sidebar">
    <h1><span class="dot" /> Android Web</h1>
    <div class="section-title">
      Устройства
      <button class="icon" title="Обновить" @click="refresh">⟳</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="!devices.length" class="muted">Нет устройств. Запустите эмулятор или подключите его по адресу ниже.</p>
    <ul>
      <li
        v-for="d in devices"
        :key="d.serial"
        :class="{ active: d.serial === selected, disabled: d.state !== 'device' }"
        @click="d.state === 'device' && emit('select', d.serial)"
      >
        <div class="name">{{ d.model ?? d.serial }}</div>
        <div class="meta">
          <span>{{ d.serial }}</span>
          <span v-if="d.state !== 'device'" class="badge warn">{{ d.state }}</span>
          <span v-else-if="d.streaming" class="badge">live</span>
        </div>
      </li>
    </ul>
    <form class="connect" @submit.prevent="connect">
      <input v-model="address" placeholder="host:5555" />
      <button type="submit">adb connect</button>
    </form>
    <p v-if="connectResult" class="muted small">{{ connectResult }}</p>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 280px;
  flex-shrink: 0;
  background: var(--panel);
  border-right: 1px solid var(--border);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
}
h1 { font-size: 17px; margin: 0 0 8px; display: flex; align-items: center; gap: 8px; }
.dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent); }
.section-title { display: flex; justify-content: space-between; align-items: center; color: var(--muted); text-transform: uppercase; font-size: 11px; letter-spacing: .08em; }
.icon { padding: 2px 8px; }
ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
li { padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: var(--panel-2); cursor: pointer; }
li:hover { border-color: var(--accent); }
li.active { border-color: var(--accent); box-shadow: inset 3px 0 0 var(--accent); }
li.disabled { opacity: .6; cursor: not-allowed; }
.name { font-weight: 600; }
.meta { display: flex; justify-content: space-between; gap: 6px; color: var(--muted); font-size: 12px; margin-top: 2px; word-break: break-all; }
.badge { color: var(--bg); background: var(--accent); border-radius: 6px; padding: 0 6px; font-weight: 600; }
.badge.warn { background: #f0b429; }
.connect { display: flex; gap: 6px; margin-top: auto; }
.connect input { flex: 1; }
.muted { color: var(--muted); }
.small { font-size: 12px; margin: 0; }
.error { color: var(--danger); }
</style>
