/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The SPA runs on 5173 and reaches the backend same-origin through this proxy, so no
// credentialed CORS is needed (documentations/06 MVP-ADR-14). In Compose, nginx does the same.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  const backend = env.VITE_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: backend, changeOrigin: false },
        '/actuator/health': { target: backend, changeOrigin: false },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      env: { VITE_API_BASE_URL: 'http://gateway.test' },
      // CI runs `npm run coverage`; the API client and the session code must keep 80% line
      // coverage (documentations/08 §4D.2, D4). A glob with no files yet adds no check.
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'lcov', 'json-summary'],
        reportsDirectory: './coverage',
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/**/*.test.{ts,tsx}',
          'src/test/**',
          'src/main.tsx',
          'src/api/schema.d.ts',
          '**/*.d.ts',
        ],
        thresholds: {
          'src/api/**': { lines: 80 },
          'src/auth/**': { lines: 80 },
        },
      },
    },
  };
});
