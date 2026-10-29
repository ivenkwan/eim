// Shared codegen logic (TG0-INF-5). Used by gen-api-client.mjs /
// gen-zod-schemas.mjs to WRITE and by check-codegen-freshness.mjs to
// COMPARE — a single source of truth so freshness can never drift from
// generation.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';
import openapiTypescript, { astToString } from 'openapi-typescript';

export const OPENAPI_DIR = join(process.cwd(), 'openapi');
export const TYPES_OUT_DIR = join(process.cwd(), 'packages/api-client/src/generated');
export const ZOD_OUT = join(process.cwd(), 'packages/schemas/src/generated/schemas.ts');

const BANNER = '// GENERATED FILE — DO NOT EDIT. Regenerate with: pnpm codegen\n\n';

export function loadSpecs() {
  return readdirSync(OPENAPI_DIR)
    .filter((f) => f.endsWith('.yaml'))
    .sort()
    .map((file) => {
      const name = file.replace(/\.yaml$/, '');
      const doc = parse(readFileSync(join(OPENAPI_DIR, file), 'utf8'));
      return { name, file, doc };
    });
}

/** openapi-typescript output (TS types) per service + a barrel file. */
export async function generateTypesOutputs() {
  const outputs = [];
  const barrel = [];
  for (const spec of loadSpecs()) {
    const ast = await openapiTypescript(spec.doc);
    const ts = astToString(ast);
    const file = `types.${spec.name}.ts`;
    outputs.push({ path: join(TYPES_OUT_DIR, file), content: BANNER + ts });
    barrel.push(`export * as ${camel(spec.name)} from './${file.replace(/\.ts$/, '')}';`);
  }
  outputs.push({
    path: join(TYPES_OUT_DIR, 'index.ts'),
    content: BANNER + barrel.join('\n') + '\n',
  });
  return outputs;
}

function camel(kebab) {
  return kebab.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

/** zod schema expressions from components.schemas (deterministic, sorted). */
function zodExpr(schema, indent) {
  if (schema.$ref !== undefined) {
    const ref = schema.$ref.replace('#/components/schemas/', '');
    return `${ref}Schema`;
  }
  switch (schema.type) {
    case 'string':
      return Array.isArray(schema.enum) && schema.enum.length > 0
        ? `z.enum([${schema.enum.map((v) => `'${String(v)}'`).join(', ')}])`
        : 'z.string()';
    case 'boolean':
      return 'z.boolean()';
    case 'integer':
      return 'z.number().int()';
    case 'number':
      return 'z.number()';
    case 'array':
      return `z.array(${zodExpr(schema.items ?? {}, indent)})`;
    case 'object': {
      const pad = ' '.repeat(indent + 2);
      const props = Object.keys(schema.properties ?? {}).sort();
      const required = new Set(schema.required ?? []);
      const lines = props.map((key) => {
        const expr = zodExpr(schema.properties[key], indent + 2);
        const opt = required.has(key) ? '' : '.optional()';
        return `${pad}${safeProp(key)}: ${expr}${opt},`;
      });
      return `z.strictObject({\n${lines.join('\n')}\n${' '.repeat(indent)}})`;
    }
    default:
      return 'z.unknown()';
  }
}

function safeProp(key) {
  return /^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(key) ? key : `'${key}'`;
}

/** Single merged zod file across all specs (namespaced by schema name). */
export function generateZodOutput() {
  const blocks = ['import { z } from \'zod\';', ''];
  const seen = new Set();
  for (const spec of loadSpecs()) {
    const schemas = spec.doc.components?.schemas ?? {};
    // emit dependency-first: a $ref to a later component would otherwise hit
    // the const temporal dead zone at import time
    const refsOf = (name) => {
      const out = new Set();
      const walk = (node) => {
        if (Array.isArray(node)) {
          node.forEach(walk);
          return;
        }
        if (node !== null && typeof node === 'object') {
          if (typeof node.$ref === 'string') {
            out.add(node.$ref.replace('#/components/schemas/', ''));
          }
          Object.values(node).forEach(walk);
        }
      };
      walk(schemas[name]);
      return out;
    };
    const ordered = [];
    const emitted = new Set();
    const pending = Object.keys(schemas).sort();
    let guard = 0;
    while (pending.length > 0 && guard++ < 10_000) {
      const idx = pending.findIndex((n) => [...refsOf(n)].every((r) => !schemas[r] || emitted.has(r)));
      const name = idx >= 0 ? pending.splice(idx, 1)[0] : pending.shift(); // cycle fallback
      emitted.add(name);
      ordered.push(name);
    }
    for (const name of ordered) {
      if (seen.has(name)) continue; // HelloResponse shared shape: first definition wins
      seen.add(name);
      blocks.push(`export const ${name}Schema = ${zodExpr(schemas[name], 0)};`, '');
    }
  }
  return { path: ZOD_OUT, content: BANNER + blocks.join('\n') };
}
