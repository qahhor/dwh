import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

/** MsNoteEntity.COLORS. */
const NOTE_COLORS = ['default', 'blue', 'green', 'yellow', 'purple', 'red'];

describe('i18n: notes', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Field labels come from the server (MsNoteQuery, MsNoteEntity); colours are named notes.color_<colour>.
    expect(
      featureI18nProblems({
        dir: 'src/app/features/notes',
        owns: ['notes.'],
        english: true,
        dynamic: [...serverLiteralKeys('notes.'), ...NOTE_COLORS.map((color) => `notes.color_${color}`)],
      }),
    ).toEqual([]);
  });
});
