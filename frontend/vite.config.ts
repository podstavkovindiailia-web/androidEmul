import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: {
    target: 'es2022',
  },
})
