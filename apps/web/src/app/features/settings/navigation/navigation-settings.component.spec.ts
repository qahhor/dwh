import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NavigationSettingsComponent } from './navigation-settings.component';
import { NavigationService } from '@core/services/navigation.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { translateTest } from '@testing/i18n-test.stub';
import { CustomNavigationItem } from '@core/models/navigation.models';
import { ComponentFixture } from '@angular/core/testing';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { NavigationItemForm } from './navigation-item-form';

/** Types into the dialog's model, as the bound fields do. */
function edit(fixture: ComponentFixture<NavigationSettingsComponent>, values: Partial<NavigationItemForm>): void {
  fixture.componentInstance.itemModel.update((item) => ({ ...item, ...values }));
}

describe('NavigationSettingsComponent', () => {
  // Dialogs render into the CDK overlay on document.body; each test starts without the last one's.
  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove()));
  const sampleItems: CustomNavigationItem[] = [
    {
      id: 1,
      code: 'superset-sales',
      title: 'Отчет по продажам (Superset)',
      sectionId: 'custom-reports',
      icon: 'bar_chart',
      targetType: 'EMBEDDED_IFRAME',
      url: 'https://superset.example.com/sales',
      openInIframe: true,
      requiredPermission: 'md.navigation.manage',
      parentId: 7,
      sortOrder: 10,
      state: 'A',
      createdAt: '2026-09-09T10:00:00Z',
      modifiedAt: '2026-09-09T10:00:00Z',
    },
    {
      id: 2,
      code: 'crm-link',
      title: 'Внешняя CRM',
      sectionId: 'custom',
      icon: 'open_in_new',
      targetType: 'EXTERNAL_LINK',
      url: 'https://crm.example.com',
      openInIframe: false,
      sortOrder: 20,
      state: 'P',
      createdAt: '2026-09-09T10:00:00Z',
      modifiedAt: '2026-09-09T10:00:00Z',
    },
  ];

  function setup(items = sampleItems) {
    const navService = {
      loadAllItems: vi.fn().mockReturnValue(of([...items])),
      createItem: vi.fn().mockReturnValue(of({ ...items[0], id: 3 })),
      updateItem: vi.fn().mockReturnValue(of({ ...items[0], title: 'Updated' })),
      setActive: vi.fn().mockReturnValue(of({ ...items[0], state: 'P' })),
      deleteItem: vi.fn().mockReturnValue(of(undefined)),
      loadPermissionChoices: vi.fn().mockReturnValue(
        of([
          { permission: 'md.navigation.manage', formName: 'Navigation', actionName: 'Manage' },
          { permission: 'tasks.items.view', formName: 'Tasks', actionName: 'View' },
        ]),
      ),
    };

    const toast = {
      success: vi.fn(),
      error: vi.fn(),
      info: vi.fn(),
      show: vi.fn(),
    };

    const i18n = {
      translate: translateTest,
      currentLang: () => 'ru',
    };

    TestBed.configureTestingModule({
      imports: [NavigationSettingsComponent],
      providers: [
        { provide: NavigationService, useValue: navService },
        { provide: ToastService, useValue: toast },
        { provide: I18nService, useValue: i18n },
      ],
    });

    const fixture = TestBed.createComponent(NavigationSettingsComponent);
    return { fixture, navService, toast, i18n };
  }

  it('loads and renders navigation items on initialization', () => {
    const { fixture, navService } = setup();
    fixture.detectChanges();

    expect(navService.loadAllItems).toHaveBeenCalled();
    expect(fixture.componentInstance.store.items()).toHaveLength(2);

    const rows = fixture.nativeElement.querySelectorAll('.nav-table [role="rowgroup"] > [role="row"]');
    expect(rows).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('Отчет по продажам (Superset)');
    expect(fixture.nativeElement.textContent).toContain('Внешняя CRM');
  });

  it('opens create modal with default values', () => {
    const { fixture } = setup();
    fixture.detectChanges();

    expect(fixture.componentInstance.isModalOpen()).toBe(false);

    fixture.componentInstance.openCreateModal();
    expect(fixture.componentInstance.isModalOpen()).toBe(true);
    expect(fixture.componentInstance.editingItem()).toBeNull();
    expect(fixture.componentInstance.itemModel().targetType).toBe('EMBEDDED_IFRAME');
    expect(fixture.componentInstance.itemModel().sortOrder).toBe(30);
  });

  it('opens edit modal and populates form fields with item data', () => {
    const { fixture } = setup();
    fixture.detectChanges();

    fixture.componentInstance.openEditModal(sampleItems[0]);

    expect(fixture.componentInstance.isModalOpen()).toBe(true);
    expect(fixture.componentInstance.editingItem()).toEqual(sampleItems[0]);
    expect(fixture.componentInstance.itemModel()).toEqual(
      expect.objectContaining({
        code: 'superset-sales',
        title: 'Отчет по продажам (Superset)',
        url: 'https://superset.example.com/sales',
      }),
    );
  });

  it('creates new navigation item and notifies success', () => {
    const { fixture, navService, toast } = setup();
    fixture.detectChanges();

    fixture.componentInstance.openCreateModal();
    edit(fixture, {
      title: 'Финансовый дашборд',
      code: 'finance-bi',
      url: 'https://bi.corp.com/dash',
      targetType: 'EMBEDDED_IFRAME',
    });

    fixture.componentInstance.saveItem();

    expect(navService.createItem).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'finance-bi',
        title: 'Финансовый дашборд',
        url: 'https://bi.corp.com/dash',
      }),
    );
    expect(toast.success).toHaveBeenCalled();
    expect(fixture.componentInstance.isModalOpen()).toBe(false);
  });

  it('updates existing navigation item when saving in edit mode', () => {
    const { fixture, navService, toast } = setup();
    fixture.detectChanges();

    fixture.componentInstance.openEditModal(sampleItems[0]);
    edit(fixture, { title: 'Обновленный отчет' });

    fixture.componentInstance.saveItem();

    expect(navService.updateItem).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        title: 'Обновленный отчет',
      }),
      undefined,
    );
    expect(toast.success).toHaveBeenCalled();
  });

  it('shows a save refused over a newer revision once and reads the menu again from its button', async () => {
    const { fixture, navService, toast } = setup();
    fixture.detectChanges();
    navService.updateItem.mockReturnValueOnce(
      throwError(() => ({ status: 409, code: 'revision_conflict', detail: 'Запись уже изменил другой пользователь' })),
    );
    fixture.componentInstance.openEditModal(sampleItems[0]);

    fixture.componentInstance.saveItem();

    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledTimes(1);
    const reads = navService.loadAllItems.mock.calls.length;
    toast.show.mock.calls[0][4].run();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.isModalOpen()).toBe(false);
    expect(navService.loadAllItems.mock.calls.length).toBe(reads + 1);
  });

  it('keeps the right and the parent of an edited item, so saving does not show it to everyone', () => {
    const { fixture, navService } = setup();
    fixture.detectChanges();

    fixture.componentInstance.openEditModal(sampleItems[0]);
    expect(fixture.componentInstance.itemModel().requiredPermission).toBe('md.navigation.manage');
    fixture.componentInstance.saveItem();

    expect(navService.updateItem).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        requiredPermission: 'md.navigation.manage',
        parentId: 7,
      }),
      undefined,
    );
  });

  it('limits a new item to the chosen right and offers the catalog by name', () => {
    const { fixture, navService } = setup();
    fixture.detectChanges();

    expect(navService.loadPermissionChoices).toHaveBeenCalled();
    fixture.componentInstance.openCreateModal();
    expect(fixture.componentInstance.itemModel().requiredPermission).toBeNull();
    edit(fixture, { title: 'Tasks board', code: 'tasks-board', url: '/tasks', requiredPermission: 'tasks.items.view' });
    fixture.componentInstance.saveItem();

    expect(navService.createItem).toHaveBeenCalledWith(
      expect.objectContaining({ requiredPermission: 'tasks.items.view' }),
    );
  });

  it("shows the required fields' errors on save, sends nothing and focuses the first one", async () => {
    const { fixture, navService } = setup();
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    fixture.componentInstance.openCreateModal();
    fixture.detectChanges();
    await fixture.whenStable();

    (document.getElementById('nav-item-form') as HTMLFormElement).requestSubmit();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(navService.createItem).not.toHaveBeenCalled();
    for (const id of ['nav-title', 'nav-code', 'nav-url']) {
      expect(document.getElementById(id)?.getAttribute('aria-invalid')).toBe('true');
    }
    expect(document.activeElement?.id).toBe('nav-title');
    fixture.nativeElement.remove();
  });

  it('derives the code of a new item from its title, and keeps the code of an existing one', () => {
    const { fixture } = setup();
    fixture.detectChanges();

    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.onTitleChange('Продажи');
    expect(fixture.componentInstance.itemModel().code).not.toBe('');
    const derived = fixture.componentInstance.itemModel().code;

    fixture.componentInstance.openEditModal(sampleItems[0]);
    fixture.componentInstance.onTitleChange('Другое');
    expect(fixture.componentInstance.itemModel().code).toBe('superset-sales');
    expect(derived).not.toBe('superset-sales');
  });

  it("puts the server's field errors under the fields instead of a toast", () => {
    const { fixture, navService, toast } = setup();
    fixture.detectChanges();
    navService.createItem.mockReturnValueOnce(
      throwError(() => ({ status: 422, errors: [{ field: 'code', message: 'Код уже занят' }] })),
    );

    fixture.componentInstance.openCreateModal();
    edit(fixture, { title: 'A', code: 'a', url: '/a' });
    fixture.componentInstance.saveItem();

    expect(fixture.componentInstance.store.fieldErrors()).toEqual({ code: 'Код уже занят' });
    expect(toast.show).not.toHaveBeenCalled();
    expect(toast.error).not.toHaveBeenCalled();
    expect(fixture.componentInstance.isModalOpen()).toBe(true);
  });

  it('closes an untouched dialog at once and asks before dropping a changed one', () => {
    const { fixture } = setup();
    fixture.detectChanges();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));

    fixture.componentInstance.openEditModal(sampleItems[0]);
    fixture.componentInstance.requestCloseModal();
    expect(confirm).not.toHaveBeenCalled();
    expect(fixture.componentInstance.isModalOpen()).toBe(false);

    fixture.componentInstance.openEditModal(sampleItems[0]);
    edit(fixture, { title: 'Changed' });
    fixture.componentInstance.requestCloseModal();
    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(fixture.componentInstance.isModalOpen()).toBe(true);
  });

  it('toggles item state and refreshes list', () => {
    const { fixture, navService } = setup();
    fixture.detectChanges();

    fixture.componentInstance.store.toggleItem(sampleItems[0]);
    // The list reloads on the next change detection.
    fixture.detectChanges();

    // The first item is active: the store asks to hide it, not to flip whatever the server holds.
    expect(navService.setActive).toHaveBeenCalledWith(1, false);
    expect(navService.loadAllItems).toHaveBeenCalledTimes(2);
  });

  it('asks before deleting and deletes on Yes', async () => {
    const { fixture, navService, toast } = setup();
    fixture.detectChanges();

    fixture.componentInstance.store.confirmDelete(sampleItems[0]);
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = document.querySelector('.smt-modal-confirm') as HTMLElement;
    expect(dialog.closest('[role="alertdialog"]')).not.toBeNull();
    expect(navService.deleteItem).not.toHaveBeenCalled();

    [...dialog.querySelectorAll<HTMLButtonElement>('button')].at(-1)!.click();

    expect(navService.deleteItem).toHaveBeenCalledWith(1, { notifyError: false });
    expect(toast.success).toHaveBeenCalled();
    await fixture.whenStable();
    expect(document.querySelector('.smt-modal-confirm')).toBeNull();
  });
});
