import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 服务间共享 token：只在 dev server 层注入（浏览器 JS 不接触），
// 仅 Vite 开发代理读取，绝不能通过 import.meta.env 暴露给浏览器。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const serviceToken = env.SERVICE_PROXY_TOKEN || env.VITE_SERVICE_TOKEN || ''

  // 注入 X-Api-Token 的代理目标（Python 服务需要鉴权）
  function withToken(target: string, rewrite?: (p: string) => string) {
    return {
      target,
      changeOrigin: true,
      ...(rewrite ? { rewrite } : {}),
      configure: (proxy: any) => {
        proxy.on('proxyReq', (proxyReq: any) => {
          if (serviceToken) proxyReq.setHeader('X-Api-Token', serviceToken)
        })
      },
    }
  }

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      proxy: {
        // Python 服务（8001/8003）：dev 层注入共享 token
        '/api/render-page': withToken('http://localhost:8001', (path) => path.replace(/^\/api\/render-page/, '/render-page')),
        '/api/count-pages': withToken('http://localhost:8001', (path) => path.replace(/^\/api\/count-pages/, '/count-pages')),
        '/api/llm':         withToken('http://localhost:8003', (p) => p.replace(/^\/api\/llm/, '')),
        // Java 服务：走 X-Auth-Token（学生/管理员登录后携带），不注入
        '/api/document': { target: 'http://localhost:39181', changeOrigin: true },
        '/api/vector':   { target: 'http://localhost:39182', changeOrigin: true },
        '/api/chat':     { target: 'http://localhost:39183', changeOrigin: true },
        '/api/workspace': { target: 'http://localhost:39183', changeOrigin: true },
        '/api/quiz':     { target: 'http://localhost:39183', changeOrigin: true },
        '/api/student':  { target: 'http://localhost:39183', changeOrigin: true },
        '/api/admin':    { target: 'http://localhost:39183', changeOrigin: true },
      },
    },
  }
})
