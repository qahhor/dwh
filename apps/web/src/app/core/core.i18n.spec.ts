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
          // Constraint codes build their keys as error.<owner module>.<code in lower case> (ADR-0030).
          ...['jobs', 'warehouse', 'units', 'versioning', 'actor'].flatMap((module) =>
            serverCodeKeys(`error.${module}.`, { toCode: (suffix) => suffix.toUpperCase() }),
          ),
        ],
      }),
    ).toEqual([]);
  });
});
