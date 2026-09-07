import { describe, expect, it } from 'vitest';
import { SearchHit } from '../models/search.models';
import { searchTarget } from './search-target';

describe('Typed search targets', () => {
  it.each([
    ['TASK', '123', ['/tasks/items', '123']],
    ['PROJECT', '9223372036854775807', ['/tasks/projects', '9223372036854775807']],
    ['USER', '42', ['/iam/users', '42']]
  ] as const)('ignores dependency URLs for %s', (entityType, id, target) => {
    expect(searchTarget({ entityType, id, title: 'x', description: '', targetUrl: 'https://invalid.test' })).toEqual(target);
  });

  it.each(['../1', '1\n', ' 1', '1 ', '0', '01', '-1', '1.0', '1e2', '9223372036854775808', '１２', ''])
    ('rejects a noncanonical bigint key %j', id => {
      expect(searchTarget({ entityType: 'USER', id, title: 'x', description: '', targetUrl: '/iam/users/1' })).toBeNull();
    });

  it('rejects unsupported categories even with an otherwise valid ID', () => {
    expect(searchTarget({ entityType: 'ADMIN', id: '1', title: 'x', description: '', targetUrl: '/settings' } as unknown as SearchHit)).toBeNull();
  });
});
