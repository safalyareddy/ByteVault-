import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the control plane so the browser never needs CORS locally.
const apiTarget = process.env.BV_API_URL ?? 'http://127.0.0.1:8090';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: apiTarget, changeOrigin: false } },
  },
});
