import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { AnnouncementsApi } from './announcements.api';

describe('AnnouncementsApi', () => {
  it('reads the managed announcements page after page until the last (plan item 3.5)', async () => {
    const get = vi
      .fn()
      .mockReturnValueOnce(of({ items: [{ id: 2 }], nextCursor: 'c2', hasMore: true }))
      .mockReturnValueOnce(of({ items: [{ id: 1 }], nextCursor: null, hasMore: false }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { get } }] });

    const all = await firstValueFrom(TestBed.inject(AnnouncementsApi).manageable());

    expect(all.map((a) => a.id)).toEqual([2, 1]);
    expect(get).toHaveBeenNthCalledWith(1, '/announcements/manage', { limit: 200 });
    expect(get).toHaveBeenNthCalledWith(2, '/announcements/manage', { limit: 200, cursor: 'c2' });
  });
});
