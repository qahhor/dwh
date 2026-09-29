import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverCodeKeys, serverKeyLiterals } from '@testing/feature-i18n';

describe('i18n: core', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // ApiService shows the text the server names (messageKey), or error.<code in lower case> without one.
    expect(
      featureI18nProblems({
        dir: 'src/app/core',
        owns: ['error.'],
        english: true,
        dynamic: [
          ...serverCodeKeys('error.', { toCode: (suffix) => suffix.toUpperCase() }),
          ...serverKeyLiterals('error.'),
          // The fnd module builds its keys from its constraint codes: error.fnd.<code in lower case>.
          ...serverCodeKeys('error.fnd.', { toCode: (suffix) => suffix.toUpperCase() }),
        ],
      }),
    ).toEqual([]);
  });
});
