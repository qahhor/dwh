/* Not vendored: tests for the modal frame (ADR-0015 rule 2); modal.service.spec.ts opens dialogs through the CDK. */
import { Component, TemplateRef, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import { redraw } from '@testing/in-screen';
import { SMTModalComponent } from './modal.component';
import type { SMTModalData } from './types/modal.types';

@Component({
  imports: [SMTModalComponent],
  template: `
    <smt-modal [smtTitle]="title" [smtShowCloseButton]="showClose">
      <p class="projected">Содержимое</p>
    </smt-modal>
  `,
})
class HostComponent {
  title = 'Новая задача';
  showClose = true;
}

/** A form a caller hands the modal service as its content. */
@Component({
  template: `
    <ng-template #form let-close="close" let-save="save">
      <button type="button" class="save" (click)="save()">Сохранить</button>
      <button type="button" class="cancel" (click)="close()">Отмена</button>
    </ng-template>
  `,
})
class FormTemplateComponent {
  readonly form = viewChild.required<TemplateRef<unknown>>('form');
}

describe('SMTModalComponent', () => {
  let container: { _addAriaLabelledBy: ReturnType<typeof vi.fn>; _removeAriaLabelledBy: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn>; containerInstance: typeof container };
  let data: SMTModalData | null;

  beforeEach(() => {
    container = { _addAriaLabelledBy: vi.fn(), _removeAriaLabelledBy: vi.fn() };
    dialogRef = { close: vi.fn(), containerInstance: container };
    data = null;
    TestBed.configureTestingModule({
      providers: [
        { provide: SMTI18nService, useValue: testI18n() },
        { provide: DialogRef, useValue: dialogRef },
        { provide: DIALOG_DATA, useFactory: () => data },
      ],
    });
  });

  function render(setup: (host: HostComponent) => void = () => undefined) {
    const fixture = TestBed.createComponent(HostComponent);
    setup(fixture.componentInstance);
    fixture.detectChanges();
    const modal = () => fixture.nativeElement.querySelector('smt-modal') as HTMLElement;
    return { fixture, modal };
  }

  it('names the dialog by its heading and closes from a named close button', () => {
    const { modal } = render();
    const heading = modal().querySelector('h2')!;

    expect(heading.textContent).toBe('Новая задача');
    expect(container._addAriaLabelledBy).toHaveBeenCalledWith(heading.id);
    expect(modal().querySelector('.projected')?.textContent).toBe('Содержимое');

    (modal().querySelector('button[aria-label="Close"]') as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('has no header and names nothing without a title or a close button', () => {
    const { modal } = render((host) => {
      host.title = '';
      host.showClose = false;
    });

    expect(modal().querySelector('.smt-modal__header')).toBeNull();
    expect(container._addAriaLabelledBy).not.toHaveBeenCalled();
  });

  it('drops the heading from the dialog name when it is gone, and when it closes', () => {
    const { fixture, modal } = render();
    const id = modal().querySelector('h2')!.id;

    fixture.componentInstance.title = '';
    redraw(fixture);
    expect(container._removeAriaLabelledBy).toHaveBeenCalledWith(id);
    expect(modal().querySelector('h2')).toBeNull();

    fixture.componentInstance.title = 'Снова';
    redraw(fixture);
    fixture.destroy();
    expect(container._removeAriaLabelledBy).toHaveBeenCalledTimes(2);
  });

  it('opened by the service, takes its title and template, whose save and close report back', () => {
    const onSave = vi.fn();
    const onClose = vi.fn();
    const templates = TestBed.createComponent(FormTemplateComponent);
    templates.detectChanges();
    data = { title: 'Из сервиса', content: templates.componentInstance.form(), onSave, onClose };
    const { modal } = render((host) => (host.title = ''));

    expect(modal().querySelector('h2')?.textContent).toBe('Из сервиса');
    expect(modal().querySelector('.projected')).toBeNull();

    (modal().querySelector('.save') as HTMLButtonElement).click();
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(dialogRef.close).toHaveBeenLastCalledWith(true);

    (modal().querySelector('.cancel') as HTMLButtonElement).click();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(dialogRef.close).toHaveBeenLastCalledWith();
  });

  it('lets the service hide the close button whatever the template says', () => {
    data = { title: 'Без крестика', showCloseButton: false };
    const { modal } = render();

    expect(modal().querySelector('button[aria-label="Close"]')).toBeNull();
    expect(modal().querySelector('h2')?.textContent).toBe('Новая задача');
  });
});
