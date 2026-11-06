import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    // Для браузера API и фронт на одном домене, поэтому куки работают как в продакшене и CORS не нужен.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
