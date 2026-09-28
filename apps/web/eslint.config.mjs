// @ts-check
// Lint of the web application (plan 10/10, item 1.1). The violations of 2026-09-28 are suppressed in
// eslint-suppressions.json (ESLint bulk suppressions): a new one fails, a fixed one must be pruned
// (npm run lint:prune), so the count only goes down. Phase 2 removes them.
import eslint from '@eslint/js';
import angular from 'angular-eslint';
import tseslint from 'typescript-eslint';

/** Plan 10/10, item 2.5: code two levels away is reached by its alias (@core, @shared, @features, @layout, @app, @testing). */
const DEEP_RELATIVE_IMPORT = {
  regex: '^\\.\\./\\.\\./',
  message: 'Import code two or more levels up by its alias (@core, @shared, @features, @layout, @app, @testing).',
};

/** Screens and their parts are app-*, kit primitives and the entity framework smt-*, shared blocks ui-*. */
function prefixRule(files, ignores, prefix) {
  return [
    {
      files,
      ignores: ['src/**/*.spec.ts', ...ignores],
      rules: {
        '@angular-eslint/component-selector': ['error', { type: 'element', prefix, style: 'kebab-case' }],
        '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix, style: 'camelCase' }],
      },
    },
  ];
}

export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', '.angular/**', 'coverage/**', 'scripts/**'],
  },
  {
    files: ['src/**/*.ts'],
    extends: [eslint.configs.recommended, ...tseslint.configs.recommended, ...angular.configs.tsRecommended],
    processor: angular.processInlineTemplates,
    rules: {
      // CODE_STYLE: kit primitives are smt-*, application blocks ui-*, screens app-*.
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: ['app', 'smt', 'ui'], style: 'kebab-case' },
      ],
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: ['app', 'smt', 'ui'], style: 'camelCase' },
      ],
      // OnPush, signal inputs, outputs and queries: the style the kit is written in.
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      '@angular-eslint/prefer-signals': 'error',
      '@angular-eslint/prefer-output-emitter-ref': 'error',
      '@typescript-eslint/no-explicit-any': 'error',
      'no-restricted-imports': ['error', { patterns: [DEEP_RELATIVE_IMPORT] }],
    },
  },
  // Plan 10/10, item 2.6: the prefix says where a component lives (see src/app/shared/README.md).
  ...prefixRule(['src/app/features/**/*.ts', 'src/app/layout/**/*.ts', 'src/app/app.component.ts'], [], 'app'),
  ...prefixRule(['src/app/shared/ui-kit/**/*.ts', 'src/app/shared/entity/**/*.ts'], [], 'smt'),
  ...prefixRule(['src/app/shared/**/*.ts'], ['src/app/shared/ui-kit/**', 'src/app/shared/entity/**'], 'ui'),
  {
    // A component asks its feature's typed data service (<feature>.api.ts), never ApiService itself (plan 10/10,
    // item 2.4): the requests of a feature and their types stay in one place a spec can fake.
    files: ['src/**/*.component.ts'],
    ignores: ['src/**/*.spec.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            DEEP_RELATIVE_IMPORT,
            {
              group: ['@core/services/api.service', '**/core/services/api.service'],
              message: 'Call the feature data service (<feature>.api.ts); only data services use ApiService.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/**/*.spec.ts', 'src/testing/**/*.ts'],
    rules: {
      // A spec and its helpers build fakes of any shape; the production rules above still hold for the code under test.
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  {
    files: ['src/**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {
      '@angular-eslint/template/prefer-control-flow': 'error',
    },
  },
);
