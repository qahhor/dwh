import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: audit', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // Column labels of both lists come from the server (AuditQuery); event labels are the enum prefix with each value.
    expect(featureI18nProblems({
      dir: 'src/app/features/audit', owns: ['audit.'], english: true,
      dynamic: [...serverLiteralKeys('audit.'), 'audit.event.I', 'audit.event.U', 'audit.event.D'],
    })).toEqual([]);
  });
});
