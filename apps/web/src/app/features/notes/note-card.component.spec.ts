import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { FormMeta } from '@core/models/form-meta.models';
import { NoteCardComponent } from './note-card.component';
import { Note } from './notes.api';
import { NOTES_FORM_META } from '@testing/form-meta';

describe('NoteCardComponent', () => {
  const note: Note = {
    id: 1,
    title: 'Модульный манифест',
    contentMd: 'Чистая архитектура',
    color: 'green',
    isPinned: true,
    attributes: {},
    createdBy: 1,
    createdAt: '2026-09-08T00:00:00Z',
    modifiedAt: '2026-09-08T00:00:00Z',
  };

  function setup(options: { meta?: FormMeta; selectable?: boolean } = {}) {
    const fixture = TestBed.createComponent(NoteCardComponent);
    fixture.componentRef.setInput('note', note);
    fixture.componentRef.setInput('meta', options.meta ?? NOTES_FORM_META);
    fixture.componentRef.setInput('selectable', options.selectable ?? false);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return { fixture, component: fixture.componentInstance, host };
  }

  it('takes the colour and the pin of the note', () => {
    const { host } = setup();

    expect(host.classList).toContain('note-card');
    expect(host.classList).toContain('color-green');
    expect(host.classList).toContain('is-pinned');
    expect(host.querySelector('.card-actions button')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('asks the screen to pin, edit, archive and delete through its buttons', () => {
    const { component, host } = setup();
    const asked: string[] = [];
    component.togglePin.subscribe(() => asked.push('pin'));
    component.edit.subscribe(() => asked.push('edit'));
    component.toggleArchive.subscribe(() => asked.push('archive'));
    component.remove.subscribe(() => asked.push('remove'));

    host.querySelectorAll<HTMLButtonElement>('.card-actions button').forEach((button) => button.click());

    expect(asked).toEqual(['pin', 'edit', 'archive', 'remove']);
  });

  // ADR-0032 5.4: an archived note offers to come back.
  it('offers to restore an archived note', () => {
    const fixture = TestBed.createComponent(NoteCardComponent);
    fixture.componentRef.setInput('note', { ...note, archived: true });
    fixture.componentRef.setInput('meta', NOTES_FORM_META);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.classList).toContain('is-archived');
    expect(host.querySelector('[data-testid="note-archive"]')?.textContent).toContain('unarchive');
  });

  it('shows no action the note form does not allow', () => {
    const { host } = setup({ meta: { ...NOTES_FORM_META, actions: [] } });

    expect(host.querySelector('.card-actions button')).toBeNull();
  });

  it('chooses the note for a bulk action when choosing is offered', () => {
    const { component, host } = setup({ selectable: true });
    const selected = vi.fn();
    component.selectedChange.subscribe(selected);

    (host.querySelector('.note-select input') as HTMLInputElement).click();

    expect(selected).toHaveBeenCalledWith(true);
  });
});
