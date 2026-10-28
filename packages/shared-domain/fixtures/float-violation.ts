// NEGATIVE LINT FIXTURE (TG0-TST-3) — deliberately violates the float
// prohibition. `fixtures/` is ignored by default lint; the verification
// harness lints this file with --no-ignore and EXPECTS ESLint to fail.
// Never import this from production code.
export const badRate: number = 0.1 + 0.2;
export const badParse: number = parseFloat('1.5');
