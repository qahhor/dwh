import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { UiFormErrorSummaryComponent, type UiFormErrorItem } from './ui-form-error-summary.component';

describe('ui-form-error-summary', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    document.body.innerHTML = '';
  });

  function render(errors: UiFormErrorItem[], threshold?: number) {
    const fixture = TestBed.createComponent(UiFormErrorSummaryComponent);
    fixture.componentRef.setInput('errors', errors);
    if (threshold !== undefined) fixture.componentRef.setInput('threshold', threshold);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('stays hidden below the threshold when every error has its field', () => {
    const element = render([
      { fieldId: 'a', label: 'Код', message: 'Обязательное поле' },
      { fieldId: 'b', label: 'Название', message: 'Обязательное поле' },
    ]);
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });

  it('collects the errors from the threshold on, with a count and links to the fields', () => {
    const field = document.createElement('input');
    field.id = 'code-field';
    document.body.appendChild(field);
    const element = render(
      [
        { fieldId: 'code-field', label: 'Код', message: 'Обязательное поле' },
        { fieldId: 'b', message: 'Слишком длинно' },
      ],
      2,
    );
    const box = element.querySelector('[role="alert"]')!;
    expect(box.querySelector('.ui-form-error-summary__title')?.textContent?.trim()).toBe('Исправьте ошибки в форме: 2');
    const link = box.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('#code-field');
    expect(link.textContent?.replace(/\s+/g, ' ').trim()).toBe('Код: Обязательное поле');
    link.click();
    expect(document.activeElement).toBe(field);
  });

  it('jumps to an enabled option inside a composite field', () => {
    const group = document.createElement('div');
    group.id = 'priority-group';
    group.setAttribute('role', 'radiogroup');
    group.innerHTML =
      '<div role="radio" tabindex="-1">Low</div>' + '<div role="radio" tabindex="0" id="priority-selected">High</div>';
    document.body.appendChild(group);
    const element = render([{ fieldId: group.id, message: 'Choose an available priority' }], 1);
    (element.querySelector('a') as HTMLAnchorElement).click();
    expect(document.activeElement?.id).toBe('priority-selected');
  });

  it.each([false, true])('focuses an editable override, nested=%s', (nested) => {
    const editor = document.createElement('div');
    editor.id = 'editable-field';
    editor.setAttribute('contenteditable', 'true');
    const field = nested ? document.createElement('div') : editor;
    if (nested) {
      field.id = 'editor-wrapper';
      field.appendChild(editor);
    }
    document.body.appendChild(field);
    const element = render([{ fieldId: field.id, message: 'Check the content' }], 1);
    (element.querySelector('a') as HTMLAnchorElement).click();
    expect(document.activeElement).toBe(editor);
  });

  it('shows an error of no drawn field at once, as plain text', () => {
    const element = render([{ message: 'Поле «Владелец» скрыто, но заполнено неверно' }]);
    const box = element.querySelector('[role="alert"]')!;
    expect(box.querySelector('a')).toBeNull();
    expect(box.querySelector('li')?.textContent?.trim()).toBe('Поле «Владелец» скрыто, но заполнено неверно');
  });

  it('leaves the address alone when the field is missing', () => {
    const element = render([{ fieldId: 'gone', message: 'x' }], 1);
    const link = element.querySelector('a') as HTMLAnchorElement;
    const event = new MouseEvent('click', { cancelable: true });
    link.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });
});
