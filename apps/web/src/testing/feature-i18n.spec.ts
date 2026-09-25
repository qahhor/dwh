import { describe, expect, it } from 'vitest';
import { featureI18nProblems } from './feature-i18n';

describe('featureI18nProblems', () => {
  it('reports an owned key nothing uses', () => {
    // notes.subtitle stays in the catalogs although no screen shows it any more.
    expect(featureI18nProblems({ dir: 'src/app/features/notes', owns: ['notes.'], english: true }))
      .toContain("'notes.subtitle' is not used: remove it, or declare it as a run-time key");
  });

  it('accepts a key the feature builds at run time once it is declared', () => {
    const problems = featureI18nProblems({ dir: 'src/app/features/notes', owns: ['notes.'], english: true, dynamic: ['notes.subtitle'] });
    expect(problems.some(problem => problem.includes("'notes.subtitle'"))).toBe(false);
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
