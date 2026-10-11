import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：dev 代理将 /api 转发至 Go 网关，规避跨域
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
