import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from '../../../../testing/feature-i18n';

describe('i18n: data overview', () => {
  it('uses only translated keys and none of its keys is dead; UPL copy is Russian only', () => {
    expect(featureI18nProblems({ dir: 'src/app/features/upl/overview', owns: ['upl.overview.'], english: false })).toEqual([]);
  });
});
