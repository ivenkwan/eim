#!/usr/bin/env node
// TG0-INF-5 / NFR-103: generated artifacts must match the OpenAPI specs.
// Regenerates in memory and diffs against the files on disk; any drift
// fails CI until `pnpm codegen` is re-run and reviewed.
import { readFileSync } from 'node:fs';
import { generateTypesOutputs, generateZodOutput } from './lib/codegen.mjs';

let dirty = 0;
for (const out of [...(await generateTypesOutputs()), generateZodOutput()]) {
  let onDisk;
  try {
    onDisk = readFileSync(out.path, 'utf8');
  } catch {
    console.error(`[freshness] MISSING generated file: ${out.path} — run: pnpm codegen`);
    dirty += 1;
    continue;
  }
  if (onDisk !== out.content) {
    console.error(`[freshness] STALE generated file: ${out.path} — run: pnpm codegen`);
    dirty += 1;
  } else {
    console.log(`[freshness] ok ${out.path}`);
  }
}
if (dirty > 0) {
  console.error(`[freshness] FAILED: ${String(dirty)} file(s) out of date`);
  process.exit(1);
}
console.log('[freshness] all generated artifacts match the OpenAPI specs');
