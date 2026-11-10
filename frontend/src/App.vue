<script setup lang="ts">
import { ref } from 'vue'
import DeviceList from './components/DeviceList.vue'
import DeviceScreen from './components/DeviceScreen.vue'

const params = new URLSearchParams(location.search)
const selected = ref<string | null>(params.get('device'))

function select(serial: string) {
  selected.value = serial
  const url = new URL(location.href)
  url.searchParams.set('device', serial)
  history.replaceState(null, '', url)
}
</script>

<template>
  <div class="layout">
    <DeviceList :selected="selected" @select="select" />
    <DeviceScreen v-if="selected" :key="selected" :serial="selected" />
    <main v-else class="empty">
      <div>
        <h2>Выберите устройство</h2>
        <p>Экран эмулятора транслируется в реальном времени (H.264 → WebCodecs), касания и клавиатура передаются обратно.</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100%; }
.empty { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--muted); text-align: center; padding: 24px; }
h2 { color: var(--text); }
</style>
