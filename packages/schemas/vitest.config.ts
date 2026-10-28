import { defineConfig } from 'vitest/config';

// TG0-TST-2 / NFR-102: >=70% line coverage for non-calculation modules.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: ['src/**/*.ts'],
      exclude: ['src/**/*.test.ts', 'src/index.ts', 'src/generated/**'],
      thresholds: { lines: 70, statements: 70, functions: 60, branches: 60 },
    },
  },
});
