import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { SimpleFieldFiles } from './new-form.models';
import { NewFormFilesComponent } from './new-form-files.component';

describe('NewFormFilesComponent (ADR-0043)', () => {
  function render(files: SimpleFieldFiles | undefined, stored: SimpleFieldFiles | null = null) {
    const fixture = TestBed.createComponent(NewFormFilesComponent);
    fixture.componentRef.setInput('files', files);
    fixture.componentRef.setInput('index', 0);
    fixture.componentRef.setInput('stored', stored);
    const emitted: SimpleFieldFiles[] = [];
    fixture.componentInstance.changed.subscribe((next) => emitted.push(next));
    fixture.detectChanges();
    return { fixture, editor: fixture.componentInstance, emitted, host: fixture.nativeElement as HTMLElement };
  }

  it('switches to several files with a count, and back to one file', () => {
    const { editor, emitted, host } = render(undefined);
    expect(host.querySelector('[data-testid="field-files-max-0"]')).toBeNull();

    editor.setMultiple(true);
    expect(emitted.at(-1)).toEqual({ multiple: true, maxFiles: 5 });
    editor.setMultiple(false);
    expect(emitted.at(-1)).toEqual({ multiple: false });
  });

  it('reads the content types as a list and keeps the size and count as numbers', () => {
    const { editor, emitted, fixture, host } = render({ multiple: true, maxFiles: 4 });
    expect(host.querySelector('[data-testid="field-files-max-0"]')).not.toBeNull();

    editor.setTypes('application/pdf, image/png ,');
    expect(emitted.at(-1)).toEqual({ multiple: true, maxFiles: 4, contentTypes: ['application/pdf', 'image/png'] });
    editor.setTypes('');
    expect(emitted.at(-1)).toEqual({ multiple: true, maxFiles: 4 });
    editor.patch({ maxSizeMb: editor.number(10) });
    expect(emitted.at(-1)).toEqual({ multiple: true, maxFiles: 4, maxSizeMb: 10 });
    editor.patch({ maxSizeMb: editor.number('') });
    expect(emitted.at(-1)).toEqual({ multiple: true, maxFiles: 4 });
    fixture.componentRef.setInput('stored', { multiple: true, maxFiles: 4 });
    fixture.detectChanges();
    expect(editor.minFiles()).toBe(4);
  });

  it('keeps one file or several of a stored field', () => {
    const { editor, emitted } = render({ multiple: false }, { multiple: false });
    editor.setMultiple(true);
    expect(emitted).toEqual([]);
  });
});
