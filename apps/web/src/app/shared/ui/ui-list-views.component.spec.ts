import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '../../core/i18n/packaged-russian';
import { ToastService } from '../../core/services/toast.service';
import { ListViewState, ListViewsApi, SavedListView } from '../list-views/list-views';
import { SMTModalService } from '../ui-kit/components/modal';
import { UiListViewsComponent } from './ui-list-views.component';
import { inScreen } from '../../../testing/in-screen';

const monthly: SavedListView = {
  id: 1,
  name: 'Месячные',
  state: { columns: { order: [], hidden: [], widths: {} }, sort: '-name', filter: [] },
  isDefault: true,
  lockVersion: 0,
  modifiedAt: '2026-09-25T00:00:00Z'
};

const api = {
  list: vi.fn(() => of([monthly])),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(() => of(undefined))
};

@Component({
  standalone: true,
  imports: [UiListViewsComponent],
  template: `<ui-list-views [state]="state" />`,
})
class HostComponent {
  readonly onApply = vi.fn();
  readonly state = new ListViewState('upl.sources', api as unknown as ListViewsApi, {
    defaultSort: () => ({ field: 'code', descending: false }),
    onApply: () => this.onApply(),
  });
}

describe('ui-list-views', () => {
  afterEach(() => {
    vi.clearAllMocks();
    document.body.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
  });

  async function render() {
    const toast = { success: vi.fn(), error: vi.fn() };
    const confirm = vi.fn(() => of(true));
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [
        { provide: ToastService, useValue: toast }
      ]
    }).compileComponents();
    // The real service opens the save dialog; only the question is answered here.
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockImplementation(confirm);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.state.load().subscribe();
    fixture.detectChanges();
    const trigger = inScreen(fixture.nativeElement).querySelector('[data-testid="views-trigger"]') as HTMLButtonElement;
    const openMenu = () => { trigger.click(); fixture.detectChanges(); };
    const item = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement | null;
    return { fixture, trigger, openMenu, item, toast, confirm };
  }

  it('is a menu button naming the view on screen', async () => {
    const { trigger, openMenu } = await render();

    expect(trigger.textContent).toContain('Месячные');
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    openMenu();

    const menu = document.querySelector('[role="menu"]')!;
    expect(menu.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['ui.views.menu']);
    const radios = [...menu.querySelectorAll('[role="menuitemradio"]')];
    expect(radios.map(radio => radio.getAttribute('aria-checked'))).toEqual(['false', 'true']);
    expect(menu.textContent).toContain(PACKAGED_RUSSIAN['ui.views.default_badge']);
  });

  it('switches to the standard view and says when the view on screen changed', async () => {
    const { fixture, trigger, openMenu, item } = await render();
    fixture.componentInstance.state.setSort({ field: 'code', descending: true });
    fixture.detectChanges();
    expect(trigger.textContent).toContain(PACKAGED_RUSSIAN['ui.views.changed']);

    openMenu();
    expect(item('views-save')).not.toBeNull();
    item('views-standard')!.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.state.activeId()).toBeNull();
    expect(fixture.componentInstance.onApply).toHaveBeenCalledTimes(1);
    expect(trigger.textContent).toContain(PACKAGED_RUSSIAN['ui.views.standard']);
  });

  it('saves a new view by name and keeps the dialog open when the name is taken', async () => {
    const { fixture, openMenu, item, toast } = await render();
    api.create.mockReturnValueOnce(throwError(() => ({ status: 422, detail: 'LIST_VIEW_NAME_TAKEN' })));
    openMenu();
    item('views-save-as')!.click();
    fixture.detectChanges();

    const submit = () => {
      (document.querySelector('button[data-testid="views-name-submit"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    };
    submit();
    expect(document.querySelector('[data-testid="views-name-error"]')?.textContent).toContain(PACKAGED_RUSSIAN['ui.views.name_required']);

    const input = document.querySelector('[data-testid="views-name"]') as HTMLInputElement;
    input.value = 'Месячные';
    input.dispatchEvent(new Event('input'));
    submit();
    expect(document.querySelector('[data-testid="views-name-error"]')?.textContent).toContain(PACKAGED_RUSSIAN['ui.views.name_taken']);
    expect(input.getAttribute('aria-invalid')).toBe('true');

    api.create.mockReturnValueOnce(of({ ...monthly, id: 2, name: 'Годовые', isDefault: false }));
    input.value = 'Годовые';
    input.dispatchEvent(new Event('input'));
    submit();
    expect(api.create).toHaveBeenLastCalledWith('upl.sources', expect.objectContaining({ name: 'Годовые', isDefault: false }));
    expect(toast.success).toHaveBeenCalledWith(PACKAGED_RUSSIAN['ui.views.saved']);
    expect(document.querySelector('[data-testid="views-name"]')).toBeNull();
    expect(fixture.componentInstance.state.active()?.name).toBe('Годовые');
  });

  it('asks before deleting a view', async () => {
    const { fixture, openMenu, item, confirm } = await render();
    openMenu();
    item('views-delete')!.click();
    fixture.detectChanges();

    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true, message: expect.stringContaining('Месячные') }));
    expect(api.remove).toHaveBeenCalledWith('upl.sources', 1);
    expect(fixture.componentInstance.state.views()).toEqual([]);
  });
});
