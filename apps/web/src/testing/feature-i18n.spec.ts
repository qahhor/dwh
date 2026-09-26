import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from './feature-i18n';

const CATALOGS = path.resolve(process.cwd(), '..', 'server', 'src', 'main', 'resources', 'i18n');
const catalog = (code: string) => JSON.parse(readFileSync(path.join(CATALOGS, `${code}.json`), 'utf8')) as Record<string, string>;
/** The real catalogs plus a notes key no screen shows, as dead copy looks. */
const withDeadNote = {
  ru: { ...catalog('ru'), 'notes.no_screen_shows_it': 'Нигде не показано' },
  en: { ...catalog('en'), 'notes.no_screen_shows_it': 'Shown nowhere' },
};

describe('featureI18nProblems', () => {
  it('reports an owned key nothing uses', () => {
    expect(featureI18nProblems({ dir: 'src/app/features/notes', owns: ['notes.'], english: true, catalogs: withDeadNote }))
      .toContain("'notes.no_screen_shows_it' is not used: remove it, or declare it as a run-time key");
  });

  it('finds no dead copy in the notes catalogs', () => {
    // The note list's field labels are named by the server (MsNoteQuery).
    expect(featureI18nProblems({
      dir: 'src/app/features/notes', owns: ['notes.'], english: true, dynamic: serverLiteralKeys('notes.'),
    })).toEqual([]);
  });

  it('accepts a key the feature builds at run time once it is declared', () => {
    const problems = featureI18nProblems({
      dir: 'src/app/features/notes', owns: ['notes.'], english: true, dynamic: ['notes.no_screen_shows_it'], catalogs: withDeadNote,
    });
    expect(problems.some(problem => problem.includes("'notes.no_screen_shows_it'"))).toBe(false);
  });

  it('reports owned keys without English where the feature ships it', () => {
    expect(featureI18nProblems({ dir: 'src/app/features/upl/overview', owns: ['upl.overview.'], english: true }))
      .toContain("'upl.overview.title' is missing from en.json");
  });

  it('reports a declared run-time key the catalogs lack', () => {
    expect(featureI18nProblems({ dir: 'src/app/features/exports', owns: ['exports.'], english: true, dynamic: ['exports.no_such_key'] }))
      .toContain("run-time key 'exports.no_such_key' is missing from ru.json");
  });

  it('refuses a folder without sources, so a moved feature does not pass silently', () => {
    expect(featureI18nProblems({ dir: 'src/app/features/no-such-feature', owns: ['x.'], english: false }))
      .toEqual(['src/app/features/no-such-feature: no source files']);
  });
});
