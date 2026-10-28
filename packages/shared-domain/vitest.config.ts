import { defineConfig } from 'vitest/config';

// TG0-TST-2 / NFR-102: >=85% line coverage on decimal/calculation-class modules.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: ['src/**/*.ts'],
      exclude: ['src/**/*.test.ts', 'src/index.ts'],
      thresholds: {
        lines: 85,
        statements: 85,
        functions: 75,
        branches: 75,
      },
    },
  },
});
