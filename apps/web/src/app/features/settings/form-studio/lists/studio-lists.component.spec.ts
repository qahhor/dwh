import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { StudioListsComponent } from './studio-lists.component';
import type { StudioListItem, StudioListSummary } from './studio-lists.store';

function list(code: string): StudioListSummary {
  return {
    code,
    name: { ru: 'Типы заявок', uz: 'Ariza turlari', en: 'Request types' },
    items: 2,
    activeItems: 1,
    revision: 3,
    createdAt: '2026-10-17T08:00:00Z',
    modifiedAt: '2026-10-17T09:00:00Z',
  };
}

function item(code: string, active: boolean): StudioListItem {
  return {
    code,
    name: { ru: `Тип ${code}`, uz: `Turi ${code}`, en: `Kind ${code}` },
    sortOrder: active ? 10 : 20,
    active,
    revision: 2,
    modifiedAt: '2026-10-17T09:00:00Z',
  };
}

describe('StudioListsComponent', () => {
  afterEach(() => vi.restoreAllMocks());

  async function render(permissions: string[] = ['studio.lists.view', 'studio.lists.manage']) {
    const api = {
      get: vi.fn((path: string) =>
        path === '/studio/lists'
          ? of({ items: [list('kinds')], hasMore: false })
          : of({ list: list('kinds'), items: [item('repair', true), item('old', false)] }),
      ),
      post: vi.fn(() => of(item('delivery', true))),
      put: vi.fn(() => of(item('old', true))),
    };
    const toast = { success: vi.fn(), error: vi.fn(), show: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [StudioListsComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    TestBed.inject(PermissionService).setPermissions(permissions);
    const fixture = TestBed.createComponent(StudioListsComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, api, toast };
  }

  it('lists the reference lists and opens one with its values and their state', async () => {
    const { fixture, root, api } = await render();
    expect(root.textContent).toContain('Типы заявок');
    expect(root.textContent).toContain('1 из 2');
    (root.querySelector('[data-testid="studio-list-open-kinds"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.get).toHaveBeenCalledWith('/studio/lists/kinds', undefined, { notifyError: false });
    const open = root.querySelector('[data-testid="studio-list-open"]')?.textContent ?? '';
    expect(open).toContain('Тип repair');
    expect(open).toContain('Предлагается');
    expect(open).toContain('Отключено');
  });

  it('adds a value with the next order and changes one from its revision', async () => {
    const { fixture, component, api, toast } = await render();
    component.store.select('kinds');
    fixture.detectChanges();
    const open = component.store.open()!;
    component.newItem(open.list);
    expect(component.initial().sortOrder).toBe(30);
    expect(component.creating()).toBe(true);
    component.save({
      code: 'delivery',
      name: { ru: 'Доставка', uz: 'Yetkazish', en: 'Delivery' },
      sortOrder: 30,
      active: true,
    });
    expect(api.post).toHaveBeenCalledWith(
      '/studio/lists/kinds/items',
      { code: 'delivery', name: { ru: 'Доставка', uz: 'Yetkazish', en: 'Delivery' }, sortOrder: 30 },
      { notifyError: false },
    );
    expect(toast.success).toHaveBeenCalled();
    component.editItem(open.list, open.items[1]);
    expect(component.creating()).toBe(false);
    component.save({ code: 'old', name: open.items[1].name, sortOrder: 20, active: true });
    expect(api.put).toHaveBeenCalledWith(
      '/studio/lists/kinds/items/old',
      { name: open.items[1].name, sortOrder: 20, active: true },
      { notifyError: false, ifMatch: 2 },
    );
  });

  it('shows the field errors of a refused list under their fields', async () => {
    const { fixture, component, api } = await render();
    api.post.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        errors: [
          { field: 'name.ru', message: 'Название: от 1 до 80 символов' },
          { field: 'unknown', message: 'Другое' },
        ],
      })),
    );
    component.newList();
    fixture.detectChanges();
    component.save({ code: 'kinds', name: { ru: ' ', uz: 'a', en: 'b' }, sortOrder: 1, active: true });
    expect(component.serverErrors()).toEqual({ ru: 'Название: от 1 до 80 символов' });
    expect(component.otherErrors()).toEqual(['Другое']);
    component.fieldEdited('ru');
    expect(component.serverErrors()).toEqual({});
  });

  it('names a taken code on its field and offers a reload for a stale revision', async () => {
    const { component, api, toast } = await render();
    api.post.mockReturnValueOnce(throwError(() => ({ status: 409, code: 'conflict', detail: 'Код занят' })));
    component.newList();
    component.save({ code: 'kinds', name: { ru: 'a', uz: 'b', en: 'c' }, sortOrder: 1, active: true });
    expect(component.serverErrors()).toEqual({ code: 'Код занят' });
    api.put.mockReturnValueOnce(throwError(() => ({ status: 409, code: 'revision_conflict' })));
    component.renameList(list('kinds'));
    component.save({ code: 'kinds', name: { ru: 'a', uz: 'b', en: 'c' }, sortOrder: 1, active: true });
    expect(toast.show).toHaveBeenCalled();
  });

  it('explains a missing right instead of showing an empty catalog', async () => {
    const { root, api } = await render([]);
    expect(root.querySelector('[data-testid="studio-lists-denied"]')).not.toBeNull();
    expect(api.get).not.toHaveBeenCalled();
    expect(root.querySelector('[data-testid="studio-lists-new"]')).toBeNull();
  });
});
