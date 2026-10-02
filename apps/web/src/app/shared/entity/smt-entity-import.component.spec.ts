import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FormMeta } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { NOTES_FORM_META } from '@testing/form-meta';
import type { EntityImport } from './entity-imports.api';
import { IMPORT_POLL_MS, SMTEntityImportComponent } from './smt-entity-import.component';

const IMPORTABLE: FormMeta = {
  ...NOTES_FORM_META,
  code: 'ms.task_types',
  actions: [...NOTES_FORM_META.actions, 'import'],
  capabilities: [...NOTES_FORM_META.capabilities, 'import'],
};

const STORED = { id: 'file-1', originalName: 'types.xlsx', sizeBytes: 10, mimeType: 'application/zip' };

function journal(changes: Partial<EntityImport>): EntityImport {
  return {
    id: 'imp-1',
    entity: 'ms.task_types',
    mode: 'dry_run',
    state: 'queued',
    rowsDone: 0,
    created: 0,
    updated: 0,
    failed: 0,
    errors: [],
    report: false,
    ...changes,
  };
}

describe('SMTEntityImportComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  async function render(meta: FormMeta, finished: EntityImport, started = journal({})) {
    const api = {
      get: vi.fn(() => of(finished)),
      post: vi.fn((path: string) => (path === '/files/upload' ? of(STORED) : of(started))),
    };
    await TestBed.configureTestingModule({
      imports: [SMTEntityImportComponent],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(SMTEntityImportComponent);
    fixture.componentRef.setInput('meta', meta);
    const imported = vi.fn();
    fixture.componentInstance.imported.subscribe(imported);
    fixture.detectChanges();
    return { fixture, api, imported, root: fixture.nativeElement as HTMLElement };
  }

  function dialog(): HTMLElement {
    return document.body.querySelector('[data-testid="entity-import-dialog"]') as HTMLElement;
  }

  it('is offered only when the entity declares IMPORT and the viewer holds its right', async () => {
    const { root } = await render(NOTES_FORM_META, journal({}));
    expect(root.querySelector('[data-testid="entity-import"]')).toBeNull();
    TestBed.resetTestingModule();
    const offered = await render(IMPORTABLE, journal({}));
    expect(offered.root.querySelector('[data-testid="entity-import"]')).not.toBeNull();
  });

  it('opens with the template in the viewer language and runs a dry run to its result', async () => {
    const finished = journal({
      state: 'done',
      rowsTotal: 2,
      rowsDone: 2,
      created: 1,
      failed: 1,
      report: true,
      errors: [{ row: 4, field: 'rows[4].color', code: 'pattern', message: 'Bad colour' }],
    });
    const { fixture, api, imported, root } = await render(IMPORTABLE, finished);
    (root.querySelector('[data-testid="entity-import"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const template = dialog().querySelector('[data-testid="entity-import-template"]') as HTMLAnchorElement;
    expect(template.getAttribute('href')).toBe('/api/v1/entities/ms.task_types/import-template?lang=ru');

    fixture.componentInstance.upload([new File(['x'], 'types.xlsx')]);
    fixture.detectChanges();
    expect(dialog().querySelector('[data-testid="entity-import-file"]')).not.toBeNull();
    fixture.componentInstance.start('dry_run');
    expect(api.post).toHaveBeenLastCalledWith(
      '/entities/ms.task_types/imports',
      { fileId: 'file-1', mode: 'dry_run', lang: 'ru' },
      { notifyError: false },
    );
    fixture.detectChanges();
    expect(dialog().querySelector('[role="progressbar"]')).not.toBeNull();

    vi.advanceTimersByTime(IMPORT_POLL_MS);
    fixture.detectChanges();
    expect(api.get).toHaveBeenCalledWith('/imports/imp-1', undefined, { notifyError: false });
    expect(dialog().querySelector('[data-testid="entity-import-summary"]')).not.toBeNull();
    const cells = Array.from(dialog().querySelectorAll('[data-testid="entity-import-errors"] td')).map((cell) =>
      cell.textContent?.trim(),
    );
    expect(cells).toEqual(['4', 'color', 'Bad colour']);
    expect(
      (dialog().querySelector('[data-testid="entity-import-report"]') as HTMLAnchorElement).getAttribute('href'),
    ).toBe('/api/v1/imports/imp-1/report');
    expect(imported).not.toHaveBeenCalled();
    fixture.componentInstance.close();
  });

  it('tells the list to reload once an applied import is done', async () => {
    const { fixture, imported } = await render(
      IMPORTABLE,
      journal({ mode: 'apply', state: 'done', created: 3 }),
      journal({ mode: 'apply' }),
    );
    fixture.componentInstance.open.set(true);
    fixture.componentInstance.upload([new File(['x'], 'types.xlsx')]);
    fixture.componentInstance.start('apply');
    vi.advanceTimersByTime(IMPORT_POLL_MS);
    expect(imported).toHaveBeenCalledTimes(1);
  });

  it('names the problem of a whole file and of a refused start', async () => {
    const { fixture, api } = await render(IMPORTABLE, journal({ state: 'failed', errorCode: 'IMPORT_STRUCTURE' }));
    fixture.componentInstance.open.set(true);
    fixture.detectChanges();
    fixture.componentInstance.upload([new File(['x'], 'types.xlsx')]);
    fixture.componentInstance.start('dry_run');
    vi.advanceTimersByTime(IMPORT_POLL_MS);
    fixture.detectChanges();
    expect(fixture.componentInstance.failure()).toBeTruthy();
    expect(dialog().querySelector('[data-testid="entity-import-failed"]')).not.toBeNull();

    api.post.mockImplementation(() => throwError(() => ({ detail: 'Busy' })));
    fixture.componentInstance.start('apply');
    expect(fixture.componentInstance.problem()).toBe('Busy');
    expect(fixture.componentInstance.fieldOf('rows[3]')).toBe('');
  });
});
