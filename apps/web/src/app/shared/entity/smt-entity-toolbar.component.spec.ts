import { TestBed } from '@angular/core/testing';
import { map, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META } from '@testing/form-meta';
import { SMTModalService, type SMTModalConfirmConfig } from '../ui-kit/components/modal';
import { ListViewState, ListViewsApi } from '../list-views/list-views';
import { SMTEntityToolbarComponent } from './smt-entity-toolbar.component';

const LIST_META = {
  code: 'ms.notes',
  fields: [],
  defaultSort: '-modifiedAt',
  defaultLimit: 50,
  maxLimit: 100,
  maxConditions: 20,
  maxInValues: 100,
};

describe('SMTEntityToolbarComponent', () => {
  async function render(meta: FormMeta, selected: number[] = []) {
    const api = {
      get: vi.fn(() => of([])),
      post: vi.fn(() =>
        of({
          action: 'delete',
          succeeded: 1,
          failed: 1,
          results: [
            { id: 1, ok: true, code: null, message: null },
            { id: 2, ok: false, code: 'not_found', message: 'Запись не найдена' },
          ],
        }),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [SMTEntityToolbarComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const modal = {
      confirm: vi
        .spyOn(TestBed.inject(SMTModalService), 'confirm')
        .mockImplementation((config: SMTModalConfirmConfig) => config.action!().pipe(map(() => true))),
    };
    const views = TestBed.runInInjectionContext(
      () =>
        new ListViewState('ms.notes', TestBed.inject(ListViewsApi), {
          defaultSort: () => null,
          onApply: () => {},
        }),
    );
    const fixture = TestBed.createComponent(SMTEntityToolbarComponent);
    fixture.componentRef.setInput('meta', meta);
    fixture.componentRef.setInput('views', views);
    fixture.componentRef.setInput('listMeta', LIST_META);
    fixture.componentRef.setInput('selected', selected);
    const done = vi.fn();
    fixture.componentInstance.bulkDone.subscribe(done);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    return { fixture, root, api, modal, done };
  }

  it('offers views, export and bulk delete by the declared capabilities', async () => {
    const { root } = await render(NOTES_FORM_META, [1]);

    expect(root.querySelector('ui-list-views')).not.toBeNull();
    expect(root.querySelector('[data-testid="export-button"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="entity-bulk-delete"]')?.textContent).toContain('1');
  });

  it('offers nothing an entity does not declare, and no delete without its right', async () => {
    const { root } = await render({ ...NOTES_FORM_META, capabilities: ['bulk'], actions: ['update'] }, [1]);

    expect(root.querySelector('ui-list-views')).toBeNull();
    expect(root.querySelector('[data-testid="export-button"]')).toBeNull();
    expect(root.querySelector('[data-testid="entity-bulk-delete"]')).toBeNull();
  });

  it('deletes the chosen records after confirmation and reports the failures', async () => {
    const { fixture, root, api, modal, done } = await render({ ...NOTES_FORM_META, capabilities: ['bulk'] }, [1, 2]);

    (root.querySelector('[data-testid="entity-bulk-delete"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(modal.confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(api.post).toHaveBeenCalledWith(
      '/entities/ms.notes/bulk',
      { action: 'delete', ids: [1, 2] },
      { notifyError: false },
    );
    expect(done).toHaveBeenCalled();
    expect(fixture.componentInstance.selected()).toEqual([]);
    expect(fixture.componentInstance.result()?.failed).toBe(1);
  });
});
