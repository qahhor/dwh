import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { FormMeta } from '@core/models/form-meta.models';
import { NoteFormDialogComponent } from './note-form-dialog.component';
import { Note } from './notes.api';
import { inScreen } from '@testing/in-screen';
import { NOTES_FORM_META, formField, withCustomField } from '@testing/form-meta';

describe('NoteFormDialogComponent', () => {
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

  function setup(options: { meta?: FormMeta; note?: Note | null } = {}) {
    const api = { get: vi.fn(() => of([])), post: vi.fn(() => of(note)), put: vi.fn(() => of(note)) };
    const toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      imports: [NoteFormDialogComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(NoteFormDialogComponent);
    fixture.componentRef.setInput('meta', options.meta ?? NOTES_FORM_META);
    fixture.componentRef.setInput('note', options.note ?? null);
    const saved = vi.fn();
    const closed = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.closed.subscribe(closed);
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      api,
      toast,
      saved,
      closed,
      screen: inScreen(fixture.nativeElement),
    };
  }

  it('starts a new note from the defaults and names the dialog after creating', () => {
    const { component, screen } = setup();

    expect(component.values()).toEqual(expect.objectContaining({ color: 'default', isPinned: false }));
    expect(screen.querySelector('.smt-modal__title').textContent).toBeTruthy();
    expect(screen.querySelector('ui-record-history')).toBeNull();
  });

  it('checks the title by the declared rules and shows the problem under the field', () => {
    const { component, api, fixture, screen } = setup();
    component.values.update((values) => ({ ...values, title: '   ' }));

    component.save();
    fixture.detectChanges();

    expect(component.problems()['title']).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
    expect(screen.querySelector('smt-entity-form [data-field="title"]').textContent).toContain(
      component.problems()['title'],
    );
  });

  it('submits a new note with its custom fields in attributes', () => {
    const topic = formField('cfTopic', 'text', { labelKey: '', label: 'Тема', attribute: 'topic' });
    const { component, api, toast, saved } = setup({ meta: withCustomField(NOTES_FORM_META, topic) });
    component.values.update((values) => ({
      ...values,
      title: ' Новая заметка ',
      contentMd: 'Текст',
      cfTopic: 'Архитектура',
    }));

    component.save();

    expect(api.post).toHaveBeenCalledWith(
      '/notes',
      {
        title: 'Новая заметка',
        contentMd: 'Текст',
        color: 'default',
        isPinned: false,
        attributes: { topic: 'Архитектура' },
      },
      { notifyError: false },
    );
    expect(toast.success).toHaveBeenCalled();
    expect(saved).toHaveBeenCalledWith(note);
  });

  it('edits the note it was given and shows its history', () => {
    const { component, api, screen } = setup({ note });

    expect(screen.querySelector('ui-record-history')).not.toBeNull();
    component.save();

    expect(api.put).toHaveBeenCalledWith('/notes/1', expect.objectContaining({ title: note.title, color: 'blue' }), {
      notifyError: false,
    });
  });

  it('puts the server rejection on the field it names and stays open', () => {
    const { component, api, toast, saved } = setup({ note });
    api.put.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        detail: 'Проверьте поля записи',
        errors: [{ field: 'color', code: 'invalid', message: 'Выберите один из вариантов' }],
      })),
    );

    component.save();

    expect(Object.keys(component.problems())).toEqual(['color']);
    expect(toast.error).not.toHaveBeenCalled();
    expect(saved).not.toHaveBeenCalled();
    expect(component.saving()).toBe(false);
  });

  it('shows the server reason when it names no field', () => {
    const { component, api, toast } = setup({ note });
    api.put.mockReturnValueOnce(throwError(() => ({ status: 422, detail: 'Заметка изменена другим пользователем' })));

    component.save();

    expect(toast.error).toHaveBeenCalledWith('Заметка изменена другим пользователем');
  });

  it('closes on request, but not while saving', () => {
    const { component, closed } = setup();

    component.saving.set(true);
    component.close();
    expect(closed).not.toHaveBeenCalled();

    component.saving.set(false);
    component.close();
    expect(closed).toHaveBeenCalledTimes(1);
  });
});
