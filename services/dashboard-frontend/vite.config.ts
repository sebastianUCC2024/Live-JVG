import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Opt-in, loopback-only review proxy. Existing backend endpoints remain unchanged.
// This lets a second frontend coexist with the running demonstration on port 5173.
const reviewProxy = process.env.LIVE_JVG_PREVIEW_PROXY === '1'
const dashboardTarget = 'http://127.0.0.1:18190'
const notificationTarget = 'http://127.0.0.1:18192'
const approvedOrigin = 'http://127.0.0.1:5173'

export default defineConfig({
  plugins: [react(), ...(reviewProxy ? [{
    name: 'readonly-local-review',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (/^\/(api(?:\/|\?|$)|ws(?:\/|\?|$))/.test(request.url ?? '')
            && request.method !== 'GET' && request.method !== 'HEAD') {
          response.statusCode = 405
          response.end('The local review proxy is read-only.')
          return
        }
        next()
      })
    },
  } satisfies import('vite').Plugin] : [])],
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: reviewProxy ? {
      '/api/alerts': { target: notificationTarget, changeOrigin: true, headers: { Origin: approvedOrigin } },
      '/api': { target: dashboardTarget, changeOrigin: true, headers: { Origin: approvedOrigin } },
      '/ws': { target: dashboardTarget, ws: true, changeOrigin: true, headers: { Origin: approvedOrigin } },
    } : undefined,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    clearMocks: true,
  },
})
