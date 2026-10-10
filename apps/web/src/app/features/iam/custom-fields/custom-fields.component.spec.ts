import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { CustomField } from '@core/models/custom-field.models';
import { CustomFieldsComponent } from './custom-fields.component';
import { inScreen } from '@testing/in-screen';
import { SMTModalService } from '@shared/ui-kit/components/modal';

describe('CustomFieldsComponent', () => {
  async function createFixture(initialFields: CustomField[] = []) {
    const toast = { success: vi.fn(), error: vi.fn(), show: vi.fn() };
    const api = {
      get: vi.fn(() => of(initialFields)),
      post: vi.fn(() => of({})),
      patch: vi.fn(() => of({})),
      delete: vi.fn(() => of({})),
    };

    await TestBed.configureTestingModule({
      imports: [CustomFieldsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
      ],
    }).compileComponents();

    return { fixture: TestBed.createComponent(CustomFieldsComponent), api, toast };
  }

  it('opens creation from the header button and labels every modal control', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();

    const buttons = inScreen(fixture.nativeElement).querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const addButton = Array.from(buttons).find((button) =>
      button.textContent?.includes('Добавить поле'),
    ) as HTMLButtonElement;
    addButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.showModal()).toBe(true);
    // smt-checkbox keeps a hidden native box (aria-hidden); the person meets its role="checkbox" instead.
    const controls = Array.from(
      inScreen(fixture.nativeElement).querySelectorAll(
        '.modal-form input:not([aria-hidden="true"]), .modal-form select, .modal-form [role="combobox"]',
      ),
    ) as HTMLElement[];
    const required = inScreen(fixture.nativeElement).querySelector('.modal-form [role="checkbox"]') as HTMLElement;
    expect(document.getElementById(required.getAttribute('aria-labelledby')!)?.textContent?.trim()).toBe(
      'Обязательное для заполнения',
    );
    expect(
      controls.filter((control) => control.getAttribute('role') === 'combobox').map((control) => control.id),
    ).toEqual(['custom-field-entity', 'custom-field-type']);
    for (const control of controls) {
      expect(control.id).not.toBe('');
      expect(inScreen(fixture.nativeElement).querySelector(`label[for="${control.id}"]`)).not.toBeNull();
    }
    expect(inScreen(fixture.nativeElement).querySelector('button[aria-label="Обновить поля"]')).not.toBeNull();

    expect(fixture.componentInstance.formData().isRequired).toBe(false);
    await fixture.whenStable(); // let the dialog finish rendering before the click
    required.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.formData().isRequired).toBe(true);
  });

  it('configures select values and sends them to the existing API', async () => {
    const { fixture, api } = await createFixture();
    fixture.detectChanges();

    const buttons = inScreen(fixture.nativeElement).querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const addButton = Array.from(buttons).find((button) =>
      button.textContent?.includes('Добавить поле'),
    ) as HTMLButtonElement;
    addButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.showModal()).toBe(true);

    const code = inScreen(fixture.nativeElement).querySelector('#custom-field-code') as HTMLInputElement;
    const name = inScreen(fixture.nativeElement).querySelector('#custom-field-name') as HTMLInputElement;
    code.value = 'status_kind';
    code.dispatchEvent(new Event('input'));
    name.value = 'Тип статуса';
    name.dispatchEvent(new Event('input'));
    const type = inScreen(fixture.nativeElement).querySelector('#custom-field-type') as HTMLButtonElement;
    type.click();
    fixture.detectChanges();
    (Array.from(document.querySelectorAll('.smt-select__option')) as HTMLElement[])[4].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.formData().fieldType).toBe('select');
    await fixture.whenStable();
    fixture.detectChanges();

    const options = inScreen(fixture.nativeElement).querySelector('#custom-field-options') as HTMLTextAreaElement;
    expect(options).not.toBeNull();
    options.value = 'Новый\nВ работе\nГотово';
    options.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.formData()).toEqual(
      expect.objectContaining({
        code: 'status_kind',
        name: 'Тип статуса',
        fieldType: 'select',
        optionsText: 'Новый\nВ работе\nГотово',
      }),
    );

    fixture.componentInstance.saveField();

    expect(api.post).toHaveBeenCalledWith(
      '/custom-fields',
      expect.objectContaining({
        options: ['Новый', 'В работе', 'Готово'],
      }),
      { notifyError: false },
    );
  });

  it('shows a save refused over a newer revision once and reads the fields again from its button', async () => {
    const field: CustomField = {
      id: 9,
      entityType: 'TASK',
      code: 'budget',
      name: 'Бюджет',
      fieldType: 'number',
      isRequired: false,
      orderNo: 10,
      createdAt: '2026-08-30T00:00:00Z',
      revision: 4,
    };
    const { fixture, api, toast } = await createFixture([field]);
    fixture.detectChanges();
    api.patch.mockReturnValueOnce(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' })),
    );
    const page = fixture.componentInstance;
    page.openEditModal(field);

    page.saveField();

    expect(api.patch).toHaveBeenCalledWith('/custom-fields/9', expect.any(Object), {
      notifyError: false,
      ifMatch: 4,
    });
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    const reads = api.get.mock.calls.length;
    toast.show.mock.calls[0][4].run();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page.showModal()).toBe(false);
    expect(api.get.mock.calls.length).toBe(reads + 1);
  });

  it('sends a field once while its save is under way, however often Save is asked', async () => {
    const { fixture, api } = await createFixture();
    api.post.mockReturnValue(NEVER);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreateModal();
    page.formData.update((form) => ({ ...form, code: 'budget', name: 'Бюджет' }));

    page.saveField();
    page.saveField();

    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('keeps the table keyboard-scrollable and confirms deletion in-app', async () => {
    const field: CustomField = {
      id: 8,
      entityType: 'TASK',
      code: 'budget',
      name: 'Бюджет',
      fieldType: 'number',
      isRequired: false,
      orderNo: 10,
      createdAt: '2026-08-30T00:00:00Z',
    };
    const { fixture, api, toast } = await createFixture([field]);
    fixture.detectChanges();

    const region = inScreen(fixture.nativeElement).querySelector('.table-wrapper[role="region"]') as HTMLElement;
    expect(region.tabIndex).toBe(0);
    expect(fixture.componentInstance.filteredFields()).toHaveLength(1);
    expect(fixture.componentInstance.canManage()).toBe(true);
    const remove = inScreen(fixture.nativeElement).querySelector('button.action-btn.danger') as HTMLButtonElement;
    expect(remove?.getAttribute('aria-label')).toBe('Удалить Бюджет');
    remove.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = document.querySelector('.smt-modal-confirm') as HTMLElement;
    expect(dialog.textContent).toContain('Удалить динамическое поле «Бюджет» (budget)?');
    expect(api.delete).not.toHaveBeenCalled();

    [...dialog.querySelectorAll<HTMLButtonElement>('button')].at(-1)!.click();
    expect(api.delete).toHaveBeenCalledWith('/custom-fields/8', { notifyError: false });
    expect(toast.success).toHaveBeenCalled();
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  it('filters fields by search query and clears search', async () => {
    const fields: CustomField[] = [
      {
        id: 1,
        entityType: 'USER',
        code: 'skype_id',
        name: 'Skype ID',
        fieldType: 'string',
        isRequired: false,
        orderNo: 1,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 2,
        entityType: 'TASK',
        code: 'cost_usd',
        name: 'Стоимость USD',
        fieldType: 'number',
        isRequired: true,
        orderNo: 2,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 3,
        entityType: 'USER',
        code: 'telegram_handle',
        name: 'Telegram Handle',
        fieldType: 'string',
        isRequired: false,
        orderNo: 3,
        createdAt: '2026-09-01T00:00:00Z',
      },
    ];

    const { fixture } = await createFixture(fields);
    fixture.detectChanges();

    expect(fixture.componentInstance.filteredFields()).toHaveLength(3);

    // Filter by 'telegram'
    fixture.componentInstance.onSearchQueryChange('telegram');
    fixture.detectChanges();

    expect(fixture.componentInstance.filteredFields()).toHaveLength(1);
    expect(fixture.componentInstance.filteredFields()[0].code).toBe('telegram_handle');

    // Clear search
    fixture.componentInstance.clearSearch();
    fixture.detectChanges();

    expect(fixture.componentInstance.filteredFields()).toHaveLength(3);
  });

  it('filters by entity tab and displays accurate tab counts', async () => {
    const fields: CustomField[] = [
      {
        id: 1,
        entityType: 'USER',
        code: 'skype_id',
        name: 'Skype ID',
        fieldType: 'string',
        isRequired: false,
        orderNo: 1,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 2,
        entityType: 'TASK',
        code: 'cost_usd',
        name: 'Стоимость USD',
        fieldType: 'number',
        isRequired: true,
        orderNo: 2,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 3,
        entityType: 'USER',
        code: 'telegram_handle',
        name: 'Telegram Handle',
        fieldType: 'string',
        isRequired: false,
        orderNo: 3,
        createdAt: '2026-09-01T00:00:00Z',
      },
    ];

    const { fixture } = await createFixture(fields);
    fixture.detectChanges();

    expect(fixture.componentInstance.getEntityCount('ALL')).toBe(3);
    expect(fixture.componentInstance.getEntityCount('USER')).toBe(2);
    expect(fixture.componentInstance.getEntityCount('TASK')).toBe(1);
    expect(fixture.componentInstance.getEntityCount('PROJECT')).toBe(0);

    fixture.componentInstance.filterByEntity('TASK');
    fixture.detectChanges();

    expect(fixture.componentInstance.filteredFields()).toHaveLength(1);
    expect(fixture.componentInstance.filteredFields()[0].code).toBe('cost_usd');
  });

  it('sorts fields by orderNo ascending then by name', async () => {
    const fields: CustomField[] = [
      {
        id: 1,
        entityType: 'USER',
        code: 'field_b',
        name: 'Поле Б',
        fieldType: 'string',
        isRequired: false,
        orderNo: 30,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 2,
        entityType: 'USER',
        code: 'field_a',
        name: 'Поле А',
        fieldType: 'string',
        isRequired: false,
        orderNo: 10,
        createdAt: '2026-09-01T00:00:00Z',
      },
      {
        id: 3,
        entityType: 'USER',
        code: 'field_c',
        name: 'Поле В',
        fieldType: 'string',
        isRequired: false,
        orderNo: 20,
        createdAt: '2026-09-01T00:00:00Z',
      },
    ];

    const { fixture } = await createFixture(fields);
    fixture.detectChanges();

    const orderedCodes = fixture.componentInstance.filteredFields().map((f) => f.code);
    expect(orderedCodes).toEqual(['field_a', 'field_c', 'field_b']);
  });

  it('sanitizes code input to lowercase and replaces spaces with underscores', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();

    fixture.componentInstance.openCreateModal();
    fixture.detectChanges();

    const input = { value: 'My Special Code' } as HTMLInputElement;
    const event = { target: input } as unknown as Event;

    fixture.componentInstance.onCodeInput(event);
    expect(fixture.componentInstance.formData().code).toBe('my_special_code');
  });

  it('shows the cleaned code in the field as the person types, even when cleaning gives the last text back', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();
    fixture.componentInstance.openCreateModal();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const code = inScreen(fixture.nativeElement).querySelector('#custom-field-code') as HTMLInputElement;
    // A typed character's input event bubbles to the smt-input host, where the page cleans the code.
    const type = async (text: string) => {
      code.value = text;
      code.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    await type('My Code');
    expect(code.value).toBe('my_code');
    expect(fixture.componentInstance.formData().code).toBe('my_code');

    // A second underscore collapses back to the text bound last.
    await type('my_code_');
    await type('my_code__');
    expect(code.value).toBe('my_code_');
    expect(fixture.componentInstance.formData().code).toBe('my_code_');
  });

  it('validates reserved codes under the code field and sends nothing', async () => {
    const { fixture, toast, api } = await createFixture();
    fixture.detectChanges();

    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.formData.update((data) => ({ ...data, code: 'status', name: 'Test' }));
    fixture.componentInstance.saveField();

    const code = fixture.componentInstance.fieldForm.code();
    expect(code.touched()).toBe(true);
    expect(code.errors()[0].message).toBe('Этот код поля зарезервирован системой');
    expect(api.post).not.toHaveBeenCalled();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('puts the field messages of a refused create under the fields instead of a toast', async () => {
    const { fixture, toast, api } = await createFixture();
    api.post.mockReturnValueOnce(
      throwError(() => ({ status: 422, errors: [{ field: 'options', code: 'X', message: 'Пустой список' }] })),
    );
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openCreateModal();
    page.formData.update((data) => ({ ...data, code: 'kind', name: 'Тип', fieldType: 'select', optionsText: 'a' }));

    page.saveField();

    expect(page.serverErrors()).toEqual({ fields: { optionsText: 'Пустой список' }, other: [] });
    expect(page.showModal()).toBe(true);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('closes an untouched dialog at once and asks before changes are lost', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));

    page.openCreateModal();
    page.requestCloseModal();
    expect(confirm).not.toHaveBeenCalled();
    expect(page.showModal()).toBe(false);

    page.openCreateModal();
    page.formData.update((data) => ({ ...data, name: 'Бюджет' }));
    page.requestCloseModal();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(page.showModal()).toBe(true);
  });

  it('toggles sort direction on repeated column click', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();

    expect(fixture.componentInstance.sortColumn).toBe('orderNo');
    expect(fixture.componentInstance.sortDirection).toBe('asc');

    fixture.componentInstance.onSortChange('orderNo');
    expect(fixture.componentInstance.sortDirection).toBe('desc');

    fixture.componentInstance.onSortChange('name');
    expect(fixture.componentInstance.sortColumn).toBe('name');
    expect(fixture.componentInstance.sortDirection).toBe('asc');
  });
});
