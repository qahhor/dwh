import { Injector, runInInjectionContext } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiService } from './api.service';
import { CommandPaletteService } from './command-palette.service';

describe('CommandPaletteService', () => {
  it('uses the backend entity query parameter contract', () => {
    const api = {
      get: vi.fn(() => of({ query: 'report', totalHits: 0, hits: [] })),
    };
    const injector = Injector.create({ providers: [{ provide: ApiService, useValue: api }] });
    const service = runInInjectionContext(injector, () => new CommandPaletteService());

    service.search('report', 'TASK', 25).subscribe();

    expect(api.get).toHaveBeenCalledWith(
      '/search',
      {
        q: 'report',
        entity: 'TASK',
        limit: 25,
      },
      { notifyError: false },
    );
  });
});
