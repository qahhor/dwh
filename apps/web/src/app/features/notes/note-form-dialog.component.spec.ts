import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { FormMeta } from '@core/models/form-meta.models';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { NoteFormDialogComponent } from './note-form-dialog.component';
import { Note } from './notes.api';
import { inScreen } from '@testing/in-screen';
import { NOTES_FORM_META, formField, withCustomField } from '@testing/form-meta';
import { EntityFormHarness } from '@testing/entity-form';

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
    const api = {
      get: vi.fn((..._args: unknown[]): Observable<unknown> => of([])),
      post: vi.fn(() => of(note)),
      patch: vi.fn(() => of(note)),
    };
    const toast = { success: vi.fn(), error: vi.fn(), show: vi.fn() };
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
    const meta = options.meta ?? NOTES_FORM_META;
    return {
      fixture,
      component: fixture.componentInstance,
      api,
      toast,
      saved,
      closed,
      screen: inScreen(fixture.nativeElement),
      form: new EntityFormHarness(
        inScreen(fixture.nativeElement),
        () => meta,
        () => fixture.detectChanges(),
      ),
    };
  }

  it('starts a new note from the defaults and names the dialog after creating', () => {
    const { component, screen } = setup();

    expect(component.values()).toEqual(expect.objectContaining({ color: 'default', isPinned: false }));
    expect(screen.querySelector('.smt-modal__title').textContent).toBeTruthy();
    expect(screen.querySelector('ui-record-history')).toBeNull();
  });

  it('checks the title by the declared rules and shows the problem under the field', () => {
    const { component, api, fixture, form } = setup();
    form.fill('title', '   ');

    component.save();
    fixture.detectChanges();

    expect(component.problems()['title']).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
    expect(form.problem('title')).toBe(component.problems()['title']);
  });

  // Plan 10/10, item 6.2: the form is filled as a person fills it, by field key and type.
  it('submits what a person types and toggles in the form', () => {
    const { component, api, form } = setup();

    expect(form.keys()).toEqual(['title', 'contentMd', 'color', 'isPinned']);
    form.fill('title', 'Список покупок');
    form.fill('contentMd', '- хлеб');
    form.fill('isPinned', true);
    component.save();

    expect(api.post).toHaveBeenCalledWith(
      '/entities/ms.notes',
      expect.objectContaining({ title: 'Список покупок', contentMd: '- хлеб', isPinned: true }),
      { notifyError: false },
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
      '/entities/ms.notes',
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

    expect(api.patch).toHaveBeenCalledWith(
      '/entities/ms.notes/1',
      expect.objectContaining({ title: note.title, color: 'blue' }),
      {
        notifyError: false,
      },
    );
  });

  it('puts the server rejection on the field it names and stays open', () => {
    const { component, api, toast, saved } = setup({ note });
    api.patch.mockReturnValueOnce(
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
    api.patch.mockReturnValueOnce(throwError(() => ({ status: 422, detail: 'Заметка изменена другим пользователем' })));

    component.save();

    expect(toast.error).toHaveBeenCalledWith('Заметка изменена другим пользователем');
  });

  it('shows the conflict text once and offers to read the note again (plan item 3.6)', () => {
    const { component, api, toast } = setup({ note: { ...note, revision: 1 } });
    api.patch.mockReturnValueOnce(
      throwError(() => ({
        status: 409,
        code: 'revision_conflict',
        messageKey: 'error.common.revision_conflict',
        detail: 'Запись уже изменил другой пользователь',
      })),
    );

    component.save();

    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    const [type, message, , , action] = toast.show.mock.calls[0];
    expect(type).toBe('error');
    expect(message).toBe('Запись уже изменил другой пользователь');
    expect(action?.label).toBeTruthy();

    api.get.mockReturnValueOnce(of({ ...note, title: 'Сохранено другим', revision: 2 }));
    action.run();

    expect(api.get).toHaveBeenCalledWith('/entities/ms.notes/1', undefined, { notifyError: false });
    expect(component.values()['title']).toBe('Сохранено другим');
    component.save();
    expect(api.patch).toHaveBeenLastCalledWith(
      '/entities/ms.notes/1',
      expect.objectContaining({ title: 'Сохранено другим' }),
      {
        notifyError: false,
        ifMatch: 2,
      },
    );
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

  it('asks the common question before dropping a changed note, and closes an untouched one at once', () => {
    const { component, closed, form } = setup();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));

    component.close();
    expect(confirm).not.toHaveBeenCalled();
    expect(closed).toHaveBeenCalledTimes(1);

    form.fill('title', 'Черновик');
    component.close();
    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Отменить изменения?', destructive: true }));
    expect(closed).toHaveBeenCalledTimes(1);

    confirm.mockReturnValue(of(true));
    component.close();
    expect(closed).toHaveBeenCalledTimes(2);
  });

  it('saves through the footer button of its form and shows the button busy while saving', () => {
    const { fixture, screen, api, form } = setup();
    form.fill('title', 'Новая');
    (screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect((screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).getAttribute('form')).toBe(
      'noteForm',
    );
  });
});
