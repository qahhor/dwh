import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { NotesComponent, Note } from './notes.component';
import { inScreen } from '../../../testing/in-screen';
import { NOTES_FORM_META, formField, withCustomField } from '../../../testing/form-meta';
import { FormMeta } from '../../core/models/form-meta.models';

describe('NotesComponent', () => {
  const mockNote: Note = {
    id: 1,
    title: 'Модульный манифест',
    contentMd: 'Чистая архитектура',
    color: 'blue',
    isPinned: false,
    attributes: {},
    createdBy: 1,
    createdAt: '2026-09-08T00:00:00Z',
    modifiedAt: '2026-09-08T00:00:00Z'
  };

  async function createFixture(meta: FormMeta = NOTES_FORM_META) {
    await TestBed.configureTestingModule({
      imports: [NotesComponent],
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            get: vi.fn((path: string) => of(path === '/notes'
              ? { items: [mockNote], nextCursor: null, hasMore: false, totalEstimated: 1 }
              : path === '/form-meta/ms.notes' ? meta : [])),
            post: vi.fn(() => of(mockNote)),
            put: vi.fn(() => of(mockNote)),
            delete: vi.fn(() => of({}))
          }
        },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(NotesComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders notes list, accessible landmark, and card details', async () => {
    const fixture = await createFixture();
    expect(fixture.componentInstance.notes().length).toBe(1);

    const region = inScreen(fixture.nativeElement).querySelector('.notes-view[role="region"]');
    expect(region).not.toBeNull();

    const titleEl = inScreen(fixture.nativeElement).querySelector('.note-title');
    expect(titleEl.textContent).toContain('Модульный манифест');

    const contentEl = inScreen(fixture.nativeElement).querySelector('.note-content');
    expect(contentEl.textContent).toContain('Чистая архитектура');
  });

  it('opens create modal and handles close event', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateModal();
    fixture.detectChanges();

    expect(fixture.componentInstance.isModalOpen()).toBe(true);
    expect(fixture.componentInstance.editingNote()).toBeNull();

    fixture.componentInstance.closeModal();
    fixture.detectChanges();
    expect(fixture.componentInstance.isModalOpen()).toBe(false);
  });

  it('checks the title by the declared rules and shows the problem under the field', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { post: ReturnType<typeof vi.fn> };
    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.formValues.update(values => ({ ...values, title: '   ' }));
    fixture.detectChanges();

    fixture.componentInstance.saveNote();
    fixture.detectChanges();

    expect(fixture.componentInstance.problems()['title']).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
    const title = inScreen(fixture.nativeElement).querySelector('smt-entity-form [data-field="title"]');
    expect(title.textContent).toContain(fixture.componentInstance.problems()['title']);
  });

  it('submits a new note with its custom fields in attributes', async () => {
    const topic = formField('cfTopic', 'text', { labelKey: '', label: 'Тема', attribute: 'topic' });
    const fixture = await createFixture(withCustomField(NOTES_FORM_META, topic));
    const api = TestBed.inject(ApiService) as unknown as { post: ReturnType<typeof vi.fn> };
    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.formValues.update(values => ({ ...values, title: ' Новая заметка ', contentMd: 'Текст', cfTopic: 'Архитектура' }));
    fixture.detectChanges();

    fixture.componentInstance.saveNote();
    fixture.detectChanges();

    expect(api.post).toHaveBeenCalledWith('/notes', {
      title: 'Новая заметка', contentMd: 'Текст', color: 'default', isPinned: false, attributes: { topic: 'Архитектура' },
    }, { notifyError: false });
    expect(fixture.componentInstance.isModalOpen()).toBe(false);
  });

  it('puts the server rejection on the field it names and keeps the dialog open', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { put: ReturnType<typeof vi.fn> };
    api.put.mockReturnValueOnce(throwError(() => ({
      status: 422, code: 'validation_failed', title: '', detail: 'Проверьте поля записи',
      errors: [{ field: 'color', code: 'invalid', message: 'Выберите один из вариантов' }],
    })));
    fixture.componentInstance.openEditModal(mockNote);
    fixture.detectChanges();

    fixture.componentInstance.saveNote();
    fixture.detectChanges();

    expect(api.put).toHaveBeenCalledWith('/notes/1', expect.objectContaining({ title: mockNote.title, color: 'blue' }), { notifyError: false });
    expect(Object.keys(fixture.componentInstance.problems())).toEqual(['color']);
    expect(fixture.componentInstance.isModalOpen()).toBe(true);
  });

  it('offers only the actions the note form allows the viewer', async () => {
    const fixture = await createFixture({ ...NOTES_FORM_META, actions: [] });
    const screen = inScreen(fixture.nativeElement);

    expect(fixture.componentInstance.canCreate()).toBe(false);
    expect(screen.querySelector('.card-actions button')).toBeNull();
  });

  it('asks the server for pinned notes on the pinned tab, and for every note again on the other', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };

    fixture.componentInstance.setTab('pinned');
    expect(api.get).toHaveBeenLastCalledWith('/notes', expect.objectContaining({
      limit: 50, filter: JSON.stringify([{ field: 'isPinned', op: 'eq', value: true }])
    }));

    fixture.componentInstance.setTab('all');
    expect(api.get.mock.lastCall?.[1]).not.toHaveProperty('filter');
  });

  it('adds the next page below the notes on screen', async () => {
    const fixture = await createFixture();
    const api = TestBed.inject(ApiService) as unknown as { get: ReturnType<typeof vi.fn> };
    api.get.mockReturnValueOnce(of({ items: [mockNote], nextCursor: 'n2', hasMore: true, totalEstimated: 2 }));
    fixture.componentInstance.loadNotes();
    fixture.detectChanges();
    expect(fixture.componentInstance.total()).toBe(2);

    const more = fixture.nativeElement.querySelector('[data-testid="notes-load-more"]') as HTMLButtonElement;
    api.get.mockReturnValueOnce(of({ items: [{ ...mockNote, id: 2 }], nextCursor: null, hasMore: false, totalEstimated: 2 }));
    more.click();
    fixture.detectChanges();

    expect(api.get).toHaveBeenLastCalledWith('/notes', expect.objectContaining({ cursor: 'n2' }));
    expect(fixture.componentInstance.notes().map(note => note.id)).toEqual([mockNote.id, 2]);
    expect(fixture.nativeElement.querySelector('[data-testid="notes-load-more"]')).toBeNull();
  });

  it('opens delete confirmation modal and confirms delete', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.deleteNote(mockNote);
    fixture.detectChanges();

    expect(fixture.componentInstance.deletingNote()).toEqual(mockNote);

    fixture.componentInstance.confirmDelete();
    fixture.detectChanges();

    expect(fixture.componentInstance.deletingNote()).toBeNull();
  });
});

