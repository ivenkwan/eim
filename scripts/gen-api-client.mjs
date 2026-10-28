#!/usr/bin/env node
// TG0-INF-5: OpenAPI -> TypeScript types (openapi-typescript) + barrel.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { generateTypesOutputs } from './lib/codegen.mjs';

for (const out of await generateTypesOutputs()) {
  mkdirSync(dirname(out.path), { recursive: true });
  writeFileSync(out.path, out.content);
  console.log(`[gen-api-client] wrote ${out.path}`);
}
