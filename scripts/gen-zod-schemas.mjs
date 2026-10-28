#!/usr/bin/env node
// TG0-INF-5: OpenAPI components.schemas -> zod runtime schemas.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { generateZodOutput } from './lib/codegen.mjs';

const out = generateZodOutput();
mkdirSync(dirname(out.path), { recursive: true });
writeFileSync(out.path, out.content);
console.log(`[gen-zod] wrote ${out.path}`);
