// @ts-check
// TG0-TST-1: maximal-strictness TypeScript linting (NFR-103).
// TG0-TST-3: floating-point prohibition in money/calculation code (NFR-01).
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/coverage/**',
      '**/generated/**',
      '**/fixtures/**',
      '**/*.config.*',
      'eslint.config.js',
    ],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: false }],
      '@typescript-eslint/restrict-template-expressions': 'error',
    },
  },
  {
    // NFR-01 / TG0-TST-3 — no IEEE-754 floats anywhere in the shared decimal domain.
    files: ['packages/shared-domain/src/**/*.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'TSTypeReference[typeName.name=/^(float|double|Float64Array)$/i]',
          message:
            'Floating-point types are prohibited in shared-domain (NFR-01). Use Money (exact string/BigInt decimal math).',
        },
        {
          selector: 'TSAsExpression > TSNumberKeyword',
          message: 'Casting to number is prohibited in shared-domain (NFR-01). Use Money / BigInt.',
        },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'parseFloat', message: 'parseFloat returns an IEEE-754 float (NFR-01). Use Money.fromString / BigInt.' },
        { name: 'Number', message: 'Number(...) coerces to IEEE-754 float (NFR-01). Use Money.fromString / BigInt.' },
      ],
    },
  },
  {
    // Negative-lint fixture target (TG0-TST-3): the verification harness lints
    // this file with --no-ignore and EXPECTS the float rules to fire.
    files: ['packages/shared-domain/fixtures/**/*.ts'],
    languageOptions: {
      parserOptions: {
        projectService: { allowDefaultProject: ['packages/shared-domain/fixtures/*.ts'] },
      },
    },
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'TSNumberKeyword',
          message: 'IEEE-754 `number` is prohibited here (NFR-01 fixture). Use Money / BigInt.',
        },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'parseFloat', message: 'parseFloat returns an IEEE-754 float (NFR-01).' },
        { name: 'Number', message: 'Number(...) coerces to IEEE-754 float (NFR-01).' },
      ],
    },
  },
);
