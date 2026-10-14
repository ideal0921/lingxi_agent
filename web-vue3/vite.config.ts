import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：dev 代理将 /api 与 /ws 转发至 Go 网关，规避跨域
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WSS 长连接代理：前端以相对地址 ws://localhost:3000/ws 连接，转发至网关 8080
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
