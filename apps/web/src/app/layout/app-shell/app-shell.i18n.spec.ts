import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from '../../../testing/feature-i18n';

describe('i18n: app shell and navigation', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    expect(featureI18nProblems({ dir: 'src/app/layout/app-shell', owns: ['layout.app_shell.', 'nav.', 'app.'], english: true })).toEqual([]);
  });
});
