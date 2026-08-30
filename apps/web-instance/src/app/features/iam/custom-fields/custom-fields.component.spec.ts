import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { CustomFieldsComponent } from './custom-fields.component';

describe('CustomFieldsComponent', () => {
  async function createFixture() {
    const api = {
      get: vi.fn(() => of([])),
      post: vi.fn(() => of({})),
      patch: vi.fn(() => of({})),
      delete: vi.fn(() => of({}))
    };

    await TestBed.configureTestingModule({
      imports: [CustomFieldsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: PermissionService, useValue: { hasPermission: () => true } }
      ]
    }).compileComponents();

    return { fixture: TestBed.createComponent(CustomFieldsComponent), api };
  }

  it('opens creation from ui-button and labels every modal control', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const addButton = Array.from(buttons)
      .find(button => button.textContent?.includes('Добавить поле')) as HTMLButtonElement;
    addButton.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.showModal).toBe(true);
    const controls = Array.from(fixture.nativeElement.querySelectorAll('.modal-form input, .modal-form select')) as Array<HTMLInputElement | HTMLSelectElement>;
    for (const control of controls) {
      expect(control.id).not.toBe('');
      expect(fixture.nativeElement.querySelector(`label[for="${control.id}"]`)).not.toBeNull();
    }
    expect(fixture.nativeElement.querySelector('.icon-refresh-btn')?.getAttribute('aria-label')).toBe('Обновить поля');
  });

  it('configures select values and sends them to the existing API', async () => {
    const { fixture, api } = await createFixture();
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const addButton = Array.from(buttons)
      .find(button => button.textContent?.includes('Добавить поле')) as HTMLButtonElement;
    addButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.showModal).toBe(true);

    const code = fixture.nativeElement.querySelector('#custom-field-code') as HTMLInputElement;
    const name = fixture.nativeElement.querySelector('#custom-field-name') as HTMLInputElement;
    code.value = 'status_kind';
    code.dispatchEvent(new Event('input'));
    name.value = 'Тип статуса';
    name.dispatchEvent(new Event('input'));
    const type = fixture.nativeElement.querySelector('#custom-field-type') as HTMLSelectElement;
    type.selectedIndex = 4;
    type.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.formData.fieldType).toBe('select');
    await fixture.whenStable();
    fixture.detectChanges();

    const options = fixture.nativeElement.querySelector('#custom-field-options') as HTMLTextAreaElement;
    expect(options).not.toBeNull();
    options.value = 'Новый\nВ работе\nГотово';
    options.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.formData).toEqual(expect.objectContaining({
      code: 'status_kind',
      name: 'Тип статуса',
      fieldType: 'select',
      optionsText: 'Новый\nВ работе\nГотово'
    }));

    fixture.componentInstance.saveField();

    expect(api.post).toHaveBeenCalledWith('/custom-fields', expect.objectContaining({
      options: ['Новый', 'В работе', 'Готово']
    }));
  });
});
