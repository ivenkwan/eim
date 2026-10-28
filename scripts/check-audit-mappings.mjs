#!/usr/bin/env node
// TG0-TST-6 / AD-9 / NFR-64: audit-coverage gate.
//
// Fails the build when:
//  - any MUTATING operation (non-GET) in openapi/*.yaml lacks inline
//    `x-audit: {action, stream}` or carries an invalid action/stream;
//  - any event topic in docs/events.yaml lacks a mapping in
//    docs/audit-mappings.yaml (or a mapping references an unknown topic).
//
// `--self-test` additionally proves the checker detects violations by
// running it against synthetic bad inputs in memory (a checker that can
// only say "pass" is worthless).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';
import { loadSpecs } from './lib/codegen.mjs';

const ROOT = process.cwd();
const AUDIT_STREAMS = new Set(['action', 'event', 'config']);
const AUDIT_ACTIONS = new Set([
  'PLAN_EDIT', 'PLAN_PUBLISH', 'MANUAL_ADJUSTMENT', 'PERIOD_LOCK', 'PERIOD_REOPEN',
  'DISPUTE_RESOLUTION', 'DATA_OVERRIDE', 'CONFIG_CHANGE', 'CONNECTOR_RUN',
  'CALCULATION_RUN', 'EXPORT_GENERATION', 'APPROVAL', 'GL_PUSH', 'TRUEUP_POSTING',
]);

/** Returns array of violation strings (empty = compliant). Read-only. */
function checkOperations(specs) {
  const violations = [];
  for (const { file, doc } of specs) {
    const paths = doc.paths ?? {};
    for (const [route, methods] of Object.entries(paths)) {
      for (const [method, op] of Object.entries(methods)) {
        if (typeof op !== 'object' || op === null) continue;
        const mutating = method.toLowerCase() !== 'get';
        const audit = op['x-audit'];
        if (audit === undefined) {
          if (mutating) violations.push(`${file} ${method.toUpperCase()} ${route}: mutating operation has no x-audit`);
          continue;
        }
        if (!mutating) continue; // GETs may opt into read-auditing; not required
        if (typeof audit !== 'object' || audit === null) {
          violations.push(`${file} ${method.toUpperCase()} ${route}: x-audit must be an object`);
          continue;
        }
        if (typeof audit.action !== 'string' || !AUDIT_ACTIONS.has(audit.action)) {
          violations.push(`${file} ${method.toUpperCase()} ${route}: invalid x-audit.action '${String(audit.action)}'`);
        }
        if (typeof audit.stream !== 'string' || !AUDIT_STREAMS.has(audit.stream)) {
          violations.push(`${file} ${method.toUpperCase()} ${route}: invalid x-audit.stream '${String(audit.stream)}'`);
        }
      }
    }
  }
  return violations;
}

function loadYaml(rel) {
  return parse(readFileSync(join(ROOT, rel), 'utf8'));
}

function checkEvents(eventsDoc, mappingsDoc) {
  const violations = [];
  const topics = (eventsDoc.topics ?? []).map((t) => t.name);
  const mappings = mappingsDoc.events ?? {};
  for (const topic of topics) {
    const m = mappings[topic];
    if (m === undefined) {
      violations.push(`docs/audit-mappings.yaml: event '${topic}' has no mapping`);
      continue;
    }
    if (!AUDIT_STREAMS.has(m.stream)) violations.push(`event '${topic}': invalid stream '${String(m.stream)}'`);
    if (!AUDIT_ACTIONS.has(m.action)) violations.push(`event '${topic}': invalid action '${String(m.action)}'`);
  }
  const known = new Set(topics);
  for (const topic of Object.keys(mappings)) {
    if (!known.has(topic)) violations.push(`docs/audit-mappings.yaml: mapping for unknown topic '${topic}'`);
  }
  return violations;
}

function selfTest() {
  const badSpec = {
    name: 'selftest',
    file: 'selftest.yaml',
    doc: { paths: { '/x': { post: { operationId: 'x' } } } },
  };
  const opFailures = checkOperations([badSpec]);
  const evFailures = checkEvents(
    { topics: [{ name: 'topic.unmapped' }] },
    { events: { 'other.topic': { stream: 'event', action: 'APPROVAL' } } },
  );
  if (opFailures.length === 0) throw new Error('self-test failed: checker missed missing x-audit');
  if (evFailures.length !== 2) throw new Error('self-test failed: checker missed event coverage violations');
  console.log('[audit-mappings] self-test ok: checker detects violations (op + event)');
}

const specs = loadSpecs();
const opViolations = checkOperations(specs);
const evViolations = checkEvents(loadYaml('docs/events.yaml'), loadYaml('docs/audit-mappings.yaml'));

if (process.argv.includes('--self-test')) selfTest();

const all = [...opViolations, ...evViolations];
if (all.length > 0) {
  console.error('[audit-mappings] FAILED — every state change and event must declare its audit mapping (AD-9, NFR-64):');
  for (const v of all) console.error(`  - ${v}`);
  process.exit(1);
}
console.log(
  `[audit-mappings] ok: ${String(specs.length)} specs checked, all mutating operations carry x-audit, all event topics mapped`,
);
