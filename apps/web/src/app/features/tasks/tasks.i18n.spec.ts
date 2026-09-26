import { describe, expect, it } from 'vitest';
import { featureI18nProblems, serverLiteralKeys } from '../../../testing/feature-i18n';

describe('i18n: tasks and projects', () => {
  it('uses only translated keys, in Russian and English, and none of its keys is dead', () => {
    // The task history names changed fields by the labels the server gives (MsTaskHistorySources); the list's
    // column labels come from MsTaskQuery, its priority labels are the enum prefix with each value.
    expect(featureI18nProblems({
      dir: 'src/app/features/tasks', owns: ['tasks.', 'task.', 'projects.'], english: true,
      dynamic: [...serverLiteralKeys('task.'), ...serverLiteralKeys('tasks.'),
        ...['low', 'medium', 'high', 'critical'].map(priority => 'tasks.priority.' + priority)],
    })).toEqual([]);
  });
});
