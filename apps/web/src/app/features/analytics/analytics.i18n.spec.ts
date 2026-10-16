import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '@testing/feature-i18n';

describe('i18n: analytics', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    expect(
      featureI18nProblems({
        dir: 'src/app/features/analytics',
        owns: ['analytics.'],
        english: true,
        dynamic: serverLiteralKeys('analytics.dashboard.rights.'),
      }),
    ).toEqual([]);
  });
});
