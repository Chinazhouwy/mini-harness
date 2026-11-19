import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期代理 /api 到本地 Spring Boot 后端；SSE 走同一代理。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        // EventSource 需要长连接；禁用缓冲由响应头保证，这里保持默认即可
      }
    }
  }
})
