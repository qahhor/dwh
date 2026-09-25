import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys } from '../../testing/feature-i18n';

describe('i18n: core', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // ApiService shows error.<code in lower case> for a server ErrorCode that has one.
    expect(featureI18nProblems({
      dir: 'src/app/core', owns: ['error.'], english: true,
      dynamic: serverCodeKeys('error.', { toCode: suffix => suffix.toUpperCase() }),
    })).toEqual([]);
  });
});
