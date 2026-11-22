import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  // 개발 중에는 /api를 gateway로 넘긴다. compose에서는 nginx가 같은 일을 한다.
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom' },
});
