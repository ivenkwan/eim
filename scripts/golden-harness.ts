/**
 * Golden calculation harness (TG0-TST-4 / NFR-100).
 *
 * Runs versioned fixtures through the shared-domain tier-slicing engine and
 * diffs against expected outputs. Deterministic: same fixture + same engine
 * version => identical results (NFR-03).
 *
 *   node scripts/golden-test.mjs            — verify all fixtures
 *   node scripts/golden-test.mjs --update   — regenerate expected outputs
 *                                             (review the diff before committing)
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Money, sliceAcrossTiers, totalPayout, type RoundingMode, type TierDefinition } from '../packages/shared-domain/src/index.ts';

const FIXTURE_DIR = join(process.cwd(), 'fixtures/golden');
const UPDATE = process.argv.includes('--update');

interface Fixture {
  name: string;
  input: {
    attainmentBefore: string;
    attainmentAfter: string;
    tiers: { name: string; floor: string; ceiling: string | null; rate: string }[];
    rounding: RoundingMode;
    finalScale: number;
  };
  expected: {
    slices: { tierName: string; sliceAmount: string; rateApplied: string; slicePayout: string }[];
    total: string;
  };
}

function run(fixture: Fixture) {
  const tiers: TierDefinition[] = fixture.input.tiers.map((t) => ({
    name: t.name,
    floor: Money.fromString(t.floor),
    ceiling: t.ceiling === null ? null : Money.fromString(t.ceiling),
    rate: t.rate,
  }));
  const slices = sliceAcrossTiers(
    Money.fromString(fixture.input.attainmentBefore),
    Money.fromString(fixture.input.attainmentAfter),
    tiers,
  );
  return {
    slices: slices.map((s) => ({
      tierName: s.tierName,
      sliceAmount: s.sliceAmount.toString(),
      rateApplied: s.rateApplied,
      slicePayout: s.slicePayout.toString(),
    })),
    total: totalPayout(slices, fixture.input.rounding, fixture.input.finalScale).toString(),
  };
}

let failed = 0;
let passed = 0;
for (const file of readdirSync(FIXTURE_DIR).filter((f) => f.endsWith('.json')).sort()) {
  const fixture: Fixture = JSON.parse(readFileSync(join(FIXTURE_DIR, file), 'utf8')) as Fixture;
  const actual = run(fixture);
  if (UPDATE) {
    fixture.expected = actual;
    writeFileSync(join(FIXTURE_DIR, file), `${JSON.stringify(fixture, null, 2)}\n`);
    console.log(`[golden] UPDATED ${file}`);
    continue;
  }
  if (JSON.stringify(actual) === JSON.stringify(fixture.expected)) {
    console.log(`[golden] PASS ${file} (${fixture.name})`);
    passed += 1;
  } else {
    failed += 1;
    console.error(`[golden] FAIL ${file} (${fixture.name})`);
    console.error(`  expected: ${JSON.stringify(fixture.expected)}`);
    console.error(`  actual:   ${JSON.stringify(actual)}`);
  }
}

if (UPDATE) {
  console.log('[golden] fixtures regenerated — review with git diff before committing (NFR-100)');
  process.exit(0);
}
if (failed > 0) {
  console.error(`[golden] FAILED: ${String(failed)} fixture(s) diverged — output changes require an approved golden update`);
  process.exit(1);
}
console.log(`[golden] all ${String(passed)} fixture(s) passed`);
