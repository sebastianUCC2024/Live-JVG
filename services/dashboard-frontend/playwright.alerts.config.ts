import { defineConfig } from '@playwright/test'
import base from './playwright.config'

// Only local fixtures: never reuse an existing server or an inherited backend URL.
export default defineConfig({
  ...base,
  testMatch: 'notification-alerts.spec.ts',
  use: { ...base.use, serviceWorkers: 'block' },
  webServer: {
    command: 'node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: false,
    env: {
      VITE_DASHBOARD_ORIGIN: 'http://127.0.0.1:8081',
      VITE_WS_URL: 'ws://127.0.0.1:8081/ws',
      VITE_NOTIFICATION_ORIGIN: 'http://127.0.0.1:8082',
      VITE_MATCH_IDS: 'match-001',
      VITE_MATCH_METADATA: '{}',
    },
  },
})
