import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { FormMeta } from '@core/models/form-meta.models';
import { SMTModalConfirmConfig, SMTModalService } from '@shared/ui-kit/components/modal';
import { NotesComponent } from './notes.component';
import { Note } from './notes.api';
import { inScreen } from '@testing/in-screen';
import { NOTES_FORM_META } from '@testing/form-meta';

describe('NotesComponent', () => {
  const note: Note = {
    id: 1,
    title: 'Модульный манифест',
    contentMd: 'Чистая архитектура',
    color: 'blue',
    isPinned: false,
    attributes: {},
    createdBy: 1,
    createdAt: '2026-09-08T00:00:00Z',
    modifiedAt: '2026-09-08T00:00:00Z',
  };
  const page = (items: Note[], nextCursor: string | null = null) => ({
    items,
    nextCursor,
    hasMore: nextCursor !== null,
    totalEstimated: items.length,
  });

  async function setup(options: { meta?: FormMeta; notes?: () => Observable<unknown> } = {}) {
    const meta = options.meta ?? NOTES_FORM_META;
    const notes = options.notes ?? (() => of(page([note])));
    const api = {
      get: vi.fn<(path: string, params?: unknown, options?: unknown) => Observable<unknown>>((path) =>
        path === '/notes'
          ? notes()
          : of(path === '/form-meta/ms.notes' ? meta : path === '/query-meta/ms.notes' ? null : []),
      ),
      post: vi.fn(() => of(note)),
      put: vi.fn(() => of(note)),
      delete: vi.fn(() => of(undefined)),
    };
    const toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      imports: [NotesComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    /** Confirms at once and runs the confirmed work, as the dialog's Yes does. */
    const modal = {
      confirm: vi
        .spyOn(TestBed.inject(SMTModalService), 'confirm')
        .mockImplementation((config: SMTModalConfirmConfig) => {
          config.action?.().subscribe({ error: () => undefined });
          return of(true);
        }),
    };
    const fixture = TestBed.createComponent(NotesComponent);
    await settle();
    async function settle() {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }
    const noteCalls = () => api.get.mock.calls.filter(([path]) => path === '/notes');
    return {
      fixture,
      component: fixture.componentInstance,
      api,
      toast,
      modal,
      settle,
      noteCalls,
      screen: inScreen(fixture.nativeElement),
    };
  }

  it('shows the notes of the first page in cards, with their count', async () => {
    const { screen, noteCalls } = await setup();

    expect(screen.querySelector('.notes-view[role="region"]')).not.toBeNull();
    expect(screen.querySelector('.note-title').textContent).toContain('Модульный манифест');
    expect(screen.querySelector('.note-content').textContent).toContain('Чистая архитектура');
    expect(screen.querySelector('.count-badge').textContent.trim()).toBe('1');
    expect(noteCalls()).toEqual([['/notes', { limit: 50 }, { notifyError: false }]]);
  });

  it('says there are no notes only once the list has answered', async () => {
    const { screen } = await setup({ notes: () => of(page([])) });

    expect(screen.querySelector('.notes-grid')).toBeNull();
    expect(screen.querySelector('.empty-state')).not.toBeNull();
  });

  it('reports a list that failed to load', async () => {
    const { toast, screen } = await setup({ notes: () => throwError(() => ({ status: 500 })) });

    expect(toast.error).toHaveBeenCalledWith(expect.any(String));
    expect(screen.querySelector('.note-card')).toBeNull();
  });

  it('offers only the actions the note form allows the viewer', async () => {
    const { component, screen } = await setup({ meta: { ...NOTES_FORM_META, actions: [] } });

    expect(component.canCreate()).toBe(false);
    expect(screen.querySelector('.card-actions button')).toBeNull();
    expect(screen.querySelector('[data-testid="entity-bulk-delete"]')).toBeNull();
  });

  it('asks the server for pinned notes on the pinned tab, and for every note again on the other', async () => {
    const { component, settle, noteCalls } = await setup();

    component.setTab('pinned');
    await settle();
    expect(noteCalls().at(-1)?.[1]).toEqual({
      limit: 50,
      filter: JSON.stringify([{ field: 'isPinned', op: 'eq', value: true }]),
    });
    expect(component.activeTab()).toBe('pinned');

    component.setTab('all');
    await settle();
    expect(noteCalls().at(-1)?.[1]).toEqual({ limit: 50 });
  });

  it('keeps the pinned tab in the view state, so a saved view carries it', async () => {
    const { component, settle, noteCalls } = await setup();

    component.setTab('pinned');
    expect(component.views.filter()).toEqual([{ field: 'isPinned', op: 'eq', value: true }]);

    component.views.filter.set([]);
    component.views.apply(null);
    await settle();
    expect(component.activeTab()).toBe('all');
    expect(noteCalls()).toHaveLength(2);
  });

  it('searches after the person stops typing', async () => {
    const { component, settle, noteCalls } = await setup();

    component.search.set('арх');
    await new Promise((resolve) => setTimeout(resolve, 350));
    await settle();

    expect(noteCalls().at(-1)?.[1]).toEqual({ limit: 50, q: 'арх' });
  });

  it('adds the next page below the notes on screen', async () => {
    const pages = [of(page([note], 'n2')), of(page([{ ...note, id: 2 }]))];
    const { component, settle, noteCalls, screen } = await setup({ notes: () => pages.shift()! });

    (screen.querySelector('[data-testid="notes-load-more"]') as HTMLButtonElement).click();
    await settle();

    expect(noteCalls().at(-1)?.[1]).toEqual({ limit: 50, cursor: 'n2' });
    expect(component.list().items.map((item) => item.id)).toEqual([1, 2]);
    expect(screen.querySelectorAll('app-note-card')).toHaveLength(2);
    expect(screen.querySelector('[data-testid="notes-load-more"]')).toBeNull();
  });

  it('opens the form for a new note and for a note to edit, and reloads after saving', async () => {
    const { component, settle, noteCalls, screen } = await setup();

    (screen.querySelector('.view-header__actions button[smt-button][smtIcon="add"]') as HTMLButtonElement).click();
    await settle();
    expect(component.editing()).toBe('new');
    expect(screen.querySelector('app-note-form-dialog')).not.toBeNull();

    component.editing.set(note);
    component.saved();
    await settle();
    expect(component.editing()).toBeNull();
    expect(noteCalls()).toHaveLength(2);
  });

  it('pins a note and reloads the list', async () => {
    const { api, settle, noteCalls, screen } = await setup();

    (screen.querySelector('.card-actions button') as HTMLButtonElement).click();
    await settle();

    expect(api.put).toHaveBeenCalledWith('/notes/1/pin', { pinned: !note.isPinned }, { notifyError: false });
    expect(noteCalls()).toHaveLength(2);
  });

  it('reports a pin that failed', async () => {
    const { api, toast, component } = await setup();
    api.put.mockReturnValueOnce(throwError(() => ({ status: 409 })));

    component.togglePin(note);

    expect(toast.error).toHaveBeenCalled();
  });

  // ADR-0032 5.4: a note goes to the archive from the revision on screen, and the list is read again.
  it('archives a note from its revision and reloads the list; a refused archive is reported', async () => {
    const { api, toast, component, settle, noteCalls, screen } = await setup();

    (screen.querySelector('[data-testid="note-archive"]') as HTMLButtonElement).click();
    await settle();

    expect(api.put).toHaveBeenCalledWith(
      '/notes/1/archived',
      { archived: true },
      { notifyError: false, ifMatch: undefined },
    );
    expect(toast.success).toHaveBeenCalled();
    expect(noteCalls()).toHaveLength(2);

    api.put.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    component.toggleArchive({ ...note, archived: true, revision: 3 });
    expect(api.put).toHaveBeenLastCalledWith(
      '/notes/1/archived',
      { archived: false },
      { notifyError: false, ifMatch: 3 },
    );
    expect(toast.error).toHaveBeenCalled();
  });

  it('deletes a note after the confirmation and reloads the list', async () => {
    const { api, modal, toast, component, settle, noteCalls } = await setup();

    component.remove(note);
    await settle();

    expect(modal.confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(api.delete).toHaveBeenCalledWith('/notes/1', { notifyError: false });
    expect(toast.success).toHaveBeenCalled();
    expect(noteCalls()).toHaveLength(2);
  });

  it('chooses notes for a bulk action through the toolbar', async () => {
    const { component, settle, screen } = await setup();
    expect(screen.querySelector('[data-testid="entity-bulk-delete"]')).toBeNull();

    component.setSelected(note, true);
    await settle();
    expect(component.isSelected(note)).toBe(true);
    expect(screen.querySelector('[data-testid="entity-bulk-delete"]')).not.toBeNull();

    component.setSelected(note, false);
    expect(component.selectedIds()).toEqual([]);
  });

  it('offers choosing notes only when the note entity has bulk actions', async () => {
    const { component, screen } = await setup({ meta: { ...NOTES_FORM_META, capabilities: ['custom_fields'] } });

    expect(component.canSelect()).toBe(false);
    expect(screen.querySelector('.note-select')).toBeNull();
  });
});
