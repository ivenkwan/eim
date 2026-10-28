#!/usr/bin/env node
// TG0-TST-4 wrapper: runs the TS golden harness via tsx.
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const tsx = join(root, 'node_modules', '.bin', 'tsx');

const result = spawnSync(tsx, ['scripts/golden-harness.ts', ...process.argv.slice(2)], {
  stdio: 'inherit',
  cwd: root,
});
process.exit(result.status ?? 1);
