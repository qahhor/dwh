import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: files', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Column labels of the files list come from the server (MfFileQuery).
    expect(featureI18nProblems({
      dir: 'src/app/features/files', owns: ['files.'], english: true,
      dynamic: serverLiteralKeys('files.'),
    })).toEqual([]);
  });
});
