import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: iam', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Column labels of the user list come from the server (MdUserQuery); its state labels are the enum
    // prefix `iam.users.state.` with each value.
    expect(featureI18nProblems({
      dir: 'src/app/features/iam', owns: ['iam.'], english: true,
      dynamic: [...serverLiteralKeys('iam.'), 'iam.users.state.A', 'iam.users.state.P'],
    })).toEqual([]);
  });
});
