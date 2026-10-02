import { describe, expect, it } from 'vitest';
import { searchTarget } from './search-target';

const hit = (entityType: string, id: string, targetUrl: string) => ({
  entityType,
  id,
  title: 'x',
  description: '',
  targetUrl,
});

describe('Search targets named by the server (ADR-0032, 10.3)', () => {
  it.each([
    ['ms.tasks', '123', '/tasks/items/123'],
    ['ms.projects', '9223372036854775807', '/tasks/projects/9223372036854775807'],
    ['md.users', '42', '/e/md.users/42'],
    ['example.orders', '7', '/e/example.orders/7'],
  ])('follows the internal path of %s', (entityType, id, target) => {
    expect(searchTarget(hit(entityType, id, target))).toBe(target);
  });

  it.each([
    'https://invalid.test/e/md.users/1',
    '//invalid.test/e/md.users/1',
    '/e/md.users/2',
    '/e/../settings/1',
    '/e/md.users/1?x=1',
    'javascript:alert(1)',
    '/E/MD.USERS/1',
    '',
  ])('refuses %j as the target of record 1', (targetUrl) => {
    expect(searchTarget(hit('md.users', '1', targetUrl))).toBeNull();
  });

  it.each(['../1', '1\n', ' 1', '1 ', '0', '01', '-1', '1.0', '1e2', '9223372036854775808', '１２', ''])(
    'rejects a noncanonical bigint key %j',
    (id) => {
      expect(searchTarget(hit('md.users', id, `/e/md.users/${id}`))).toBeNull();
    },
  );
});
