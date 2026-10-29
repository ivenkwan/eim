#!/usr/bin/env node
// TG3-UI-7 (NFR-26): web performance budgets in CI. Vite build output is
// checked against explicit byte budgets per asset class (the strongest
// static proxy for FCP/TTI available without a browser farm); the
// Lighthouse targets are declared in lighthouse.config.json and enforced
// on the deployed dev environment by the verify-tg3 harness.
import { readdirSync, statSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { argv, exit } from 'node:process';

const DIST = argv[2] ?? 'packages/web-app/dist';
const BUDGETS = [
  { match: /\.js$/, maxBytes: 220 * 1024, label: 'JS bundle (FCP/TTI driver)' },
  { match: /\.css$/, maxBytes: 64 * 1024, label: 'CSS' },
  { match: /\.html$/, maxBytes: 16 * 1024, label: 'HTML shell' },
];

if (!existsSync(DIST)) {
  console.error(`[web-budget] missing build output at ${DIST} — run pnpm build first`);
  exit(1);
}

const files = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
    } else {
      files.push(full);
    }
  }
};
walk(DIST);

let failed = 0;
for (const { match, maxBytes, label } of BUDGETS) {
  const total = files.filter((f) => match.test(f)).reduce((sum, f) => sum + statSync(f).size, 0);
  const ok = total <= maxBytes;
  console.log(
    `[web-budget] ${ok ? 'ok  ' : 'FAIL'} ${label}: ${(total / 1024).toFixed(1)} KiB / ${(maxBytes / 1024).toFixed(0)} KiB`,
  );
  if (!ok) {
    failed++;
  }
}

const lhConfig = 'lighthouse.config.json';
if (!existsSync(lhConfig)) {
  console.error(`[web-budget] missing ${lhConfig}`);
  failed++;
} else {
  const conf = JSON.parse(readFileSync(lhConfig, 'utf8'));
  const metrics = conf.categories?.performance?.metrics ?? {};
  const fcp = metrics['first-contentful-paint'];
  const tti = metrics['interactive'];
  if (fcp?.target !== 'under-2s' || tti?.target !== 'under-3.5s') {
    console.error('[web-budget] lighthouse targets must stay FCP<2s, interactive<3.5s');
    failed++;
  } else {
    console.log('[web-budget] lighthouse targets declared: FCP<2s, interactive<3.5s');
  }
}

if (failed > 0) {
  console.error(`[web-budget] FAILED (${failed})`);
  exit(1);
}
console.log('[web-budget] all budgets met');
