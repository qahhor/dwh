import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from '@testing/feature-i18n';

describe('i18n: settings', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Search settings name the entities and fields the search indexes by their own keys (ADR-0032, 10.3).
    expect(
      featureI18nProblems({
        dir: 'src/app/features/settings',
        owns: ['settings.', 'modules.'],
        english: true,
      }),
    ).toEqual([]);
  });
});
