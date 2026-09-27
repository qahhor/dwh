import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: app shell and navigation', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Menu items of declared entities are labelled by the server (EntityMenu, roadmap item 57).
    expect(
      featureI18nProblems({
        dir: 'src/app/layout/app-shell',
        owns: ['layout.app_shell.', 'nav.', 'app.'],
        english: true,
        dynamic: serverLiteralKeys('nav.'),
      }),
    ).toEqual([]);
  });
});
