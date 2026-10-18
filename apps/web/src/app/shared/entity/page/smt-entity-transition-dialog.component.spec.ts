import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { MAX_TRANSITION_COMMENT, SMTEntityTransitionDialogComponent } from './smt-entity-transition-dialog.component';

describe('SMTEntityTransitionDialogComponent', () => {
  function render(required: boolean) {
    const fixture = TestBed.createComponent(SMTEntityTransitionDialogComponent);
    fixture.componentRef.setInput('title', 'Отклонить');
    fixture.componentRef.setInput('required', required);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    const dialog = fixture.componentInstance;
    const confirmed = vi.fn();
    const closed = vi.fn();
    dialog.confirmed.subscribe(confirmed);
    dialog.closed.subscribe(closed);
    return { fixture, dialog, confirmed, closed };
  }

  it('asks a required comment before the transition and sends it trimmed', () => {
    const { fixture, dialog, confirmed } = render(true);
    expect(dialog.max).toBe(MAX_TRANSITION_COMMENT);
    expect(document.querySelector('#entity-transition-comment')).not.toBeNull();

    dialog.submit();
    expect(confirmed).not.toHaveBeenCalled();
    expect(dialog.error()).toBe(translateTest('ui.entity_page.transition_comment_missing'));

    dialog.edit(42);
    expect(dialog.comment()).toBe('');
    dialog.edit('  Цены выше договорных ');
    dialog.submit();
    expect(confirmed).toHaveBeenCalledWith('Цены выше договорных');

    fixture.componentRef.setInput('serverError', 'Слишком длинный комментарий');
    expect(dialog.error()).toBe('Слишком длинный комментарий');
  });

  it('lets an optional comment stay empty, holds while busy and starts empty when opened again', () => {
    const { fixture, dialog, confirmed, closed } = render(false);
    dialog.submit();
    expect(confirmed).toHaveBeenCalledWith('');
    expect(dialog.error()).toBe('');

    fixture.componentRef.setInput('busy', true);
    dialog.submit();
    dialog.close();
    expect(confirmed).toHaveBeenCalledTimes(1);
    expect(closed).not.toHaveBeenCalled();

    fixture.componentRef.setInput('busy', false);
    dialog.close();
    expect(closed).toHaveBeenCalledTimes(1);

    dialog.edit('Черновик');
    fixture.componentRef.setInput('open', false);
    fixture.detectChanges();
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    expect(dialog.comment()).toBe('');
  });
});
