import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: notes', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Field labels of the note list come from the server (MsNoteQuery).
    expect(featureI18nProblems({
      dir: 'src/app/features/notes', owns: ['notes.'], english: true, dynamic: serverLiteralKeys('notes.'),
    })).toEqual([]);
  });
});
