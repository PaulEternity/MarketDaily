import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

/** 开发和预览统一把同源 API 请求转发给 Spring Boot。 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'MARKETDAILY_')
  // 后端基址仅由 Vite 服务端读取，不写入浏览器产物。
  const proxy = { '/api': env.MARKETDAILY_API_TARGET || 'http://127.0.0.1:8080' }
  return { plugins: [vue()], server: { proxy }, preview: { proxy } }
})
