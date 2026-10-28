import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.tsx'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: ['src/**/*.tsx', 'src/**/*.ts'],
      exclude: ['src/**/*.test.tsx', 'src/main.tsx'],
      thresholds: { lines: 70, statements: 70, functions: 60, branches: 60 },
    },
  },
});
