// Lint of the style sheets (plan 10/10, item 1.1): mistakes a browser drops silently, and colours
// outside the design tokens. Inline component styles are covered by contrast:audit (ADR-0012).
/** @type {import('stylelint').Config} */
export default {
  ignoreFiles: ['dist/**', 'node_modules/**', '.angular/**', 'coverage/**'],
  rules: {
    // A browser skips what it does not know: each of these is a rule that never applied.
    'at-rule-no-unknown': [
      true,
      {
        ignoreAtRules: [
          'theme',
          'source',
          'utility',
          'variant',
          'custom-variant',
          'apply',
          'reference',
          'plugin',
          'config',
          'tailwind',
        ],
      },
    ],
    'property-no-unknown': true,
    'unit-no-unknown': true,
    'color-no-invalid-hex': true,
    'declaration-property-value-no-unknown': true,
    'media-feature-name-no-unknown': true,
    'selector-pseudo-class-no-unknown': [true, { ignorePseudoClasses: ['host', 'host-context'] }],
    'selector-pseudo-element-no-unknown': [true, { ignorePseudoElements: ['ng-deep'] }],
    'selector-type-no-unknown': [true, { ignore: ['custom-elements'] }],
    'function-calc-no-unspaced-operator': true,
    'string-no-newline': true,
    'no-invalid-double-slash-comments': true,
    'no-invalid-position-at-import-rule': true,
    'keyframe-declaration-no-important': true,
    'font-family-no-missing-generic-family-keyword': true,
    // A later duplicate silently wins over the earlier one.
    'declaration-block-no-duplicate-properties': [true, { ignore: ['consecutive-duplicates-with-different-syntaxes'] }],
    'declaration-block-no-duplicate-custom-properties': true,
    'declaration-block-no-shorthand-property-overrides': true,
    'no-duplicate-selectors': true,
    'no-duplicate-at-import-rules': true,
    'block-no-empty': true,
    // Colours come from the tokens of styles.css, so that both themes keep their contrast.
    'color-no-hex': true,
    'color-named': 'never',
  },
  overrides: [
    {
      // The token file defines the colours; the note palette is a user choice (see contrast-audit.mjs).
      files: ['src/styles.css', 'src/tailwind.css', 'src/app/features/notes/notes.component.css'],
      rules: { 'color-no-hex': null, 'color-named': null },
    },
  ],
};
