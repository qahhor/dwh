import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { EntityReportsApi, type ReportState, type SavedReport } from './entity-reports';
import { SMTEntityReportSavedComponent } from './smt-entity-report-saved.component';

const STATE: ReportState = { groupBy: [{ field: 'status' }], measures: [{ op: 'count' }], filter: [], chart: 'bar' };

function report(id: number, name: string, kind: SavedReport['kind'] = 'report'): SavedReport {
  return { id, name, kind, state: STATE, lockVersion: 1 } as SavedReport;
}

/* The person's saved reports of a list (ADR-0032 10.2). */
describe('SMTEntityReportSavedComponent', () => {
  const lists = new Map<string, Subject<SavedReport[]>>();
  const reports = {
    list: vi.fn((code: string) => {
      const answer = new Subject<SavedReport[]>();
      lists.set(code, answer);
      return answer.asObservable();
    }),
    save: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  };
  const toast = { success: vi.fn(), error: vi.fn() };
  const modal = { confirm: vi.fn(() => of(true)) };

  beforeEach(() => {
    lists.clear();
    for (const fake of [reports.list, reports.save, reports.update, reports.remove, toast.success, toast.error]) {
      fake.mockClear();
    }
    TestBed.configureTestingModule({
      providers: [
        { provide: EntityReportsApi, useValue: reports },
        { provide: ToastService, useValue: toast },
        { provide: SMTModalService, useValue: modal },
      ],
    });
  });

  function render(listCode = 'test.orders') {
    const fixture = TestBed.createComponent(SMTEntityReportSavedComponent);
    fixture.componentRef.setInput('listCode', listCode);
    fixture.componentRef.setInput('state', STATE);
    fixture.detectChanges();
    return { fixture, saved: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function answer(code: string, items: SavedReport[]) {
    lists.get(code)!.next(items);
    lists.get(code)!.complete();
  }

  it('reads the reports of the list and offers them by name, marking the widgets', async () => {
    const { fixture, saved } = render();
    expect(reports.list).toHaveBeenCalledWith('test.orders');

    answer('test.orders', [report(1, 'Все'), report(2, 'На панели', 'widget')]);
    await settle(fixture);
    expect(saved.options()).toEqual([
      { id: 1, label: 'Все', icon: 'summarize', subLabel: undefined },
      { id: 2, label: 'На панели', icon: 'dashboard', subLabel: translateTest('ui.report.on_dashboard') },
    ]);
  });

  it('shows only the reports of the list on screen when an earlier answer comes late', async () => {
    const { fixture, saved } = render('test.orders');
    fixture.componentRef.setInput('listCode', 'test.tasks');
    fixture.detectChanges();

    answer('test.tasks', [report(5, 'Задачи')]);
    answer('test.orders', [report(1, 'Заказы')]);
    await settle(fixture);
    expect(saved.saved().map((item) => item.name)).toEqual(['Задачи']);
  });

  it('shows none when the reports cannot be read', async () => {
    const { fixture, saved } = render();
    lists.get('test.orders')!.error({ status: 500 });
    await settle(fixture);
    expect(saved.saved()).toEqual([]);
  });

  it('opens a chosen report', async () => {
    const { fixture, saved } = render();
    answer('test.orders', [report(1, 'Все')]);
    await settle(fixture);
    const opened = vi.fn();
    saved.opened.subscribe(opened);

    saved.pick(1);
    expect(saved.active()?.name).toBe('Все');
    expect(opened).toHaveBeenCalledWith(STATE);
    saved.pick(null);
    expect(opened).toHaveBeenCalledTimes(1);
  });

  it('asks for a name, then saves what is on screen as a new report or widget', async () => {
    const { fixture, saved } = render();
    answer('test.orders', [report(3, 'Я')]);
    await settle(fixture);

    saved.openSaveAs();
    saved.submitSaveAs();
    expect(saved.error()).toBe(translateTest('ui.report.name_required'));
    expect(reports.save).not.toHaveBeenCalled();

    reports.save.mockReturnValue(of(report(4, 'Аа', 'widget')));
    saved.name.set('  Аа ');
    saved.asWidget.set(true);
    saved.submitSaveAs();
    expect(reports.save).toHaveBeenCalledWith('test.orders', 'Аа', 'widget', STATE);
    expect(saved.saveAsOpen()).toBe(false);
    expect(saved.activeId()).toBe(4);
    expect(saved.saved().map((item) => item.name)).toEqual(['Аа', 'Я']);
    expect(toast.success).toHaveBeenCalled();
  });

  it('shows the problem of a refused save under the name', async () => {
    const { saved } = render();
    reports.save.mockReturnValue(throwError(() => ({ detail: 'Такое имя уже есть' })));
    saved.name.set('Все');
    saved.submitSaveAs();
    expect(saved.error()).toBe('Такое имя уже есть');
    expect(saved.busy()).toBe(false);
  });

  it('writes the state on screen into a report, and moves it on and off the dashboard', async () => {
    const { fixture, saved } = render();
    answer('test.orders', [report(1, 'Все')]);
    await settle(fixture);

    reports.update.mockImplementation((_code: string, written: SavedReport) => of({ ...written, lockVersion: 2 }));
    saved.togglePin(saved.saved()[0]);
    expect(reports.update).toHaveBeenCalledWith('test.orders', expect.objectContaining({ id: 1, kind: 'widget' }));
    expect(saved.saved()[0].kind).toBe('widget');

    reports.update.mockReturnValue(throwError(() => ({})));
    saved.saveActive(saved.saved()[0]);
    expect(toast.error).toHaveBeenCalledWith(translateTest('ui.report.save_failed'));
  });

  it('deletes a report after the person confirms', async () => {
    const { fixture, saved } = render();
    answer('test.orders', [report(1, 'Все'), report(2, 'Ещё')]);
    await settle(fixture);
    saved.pick(1);

    reports.remove.mockReturnValue(of(undefined));
    saved.remove(saved.saved()[0]);
    expect(modal.confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(reports.remove).toHaveBeenCalledWith('test.orders', 1);
    expect(saved.saved().map((item) => item.id)).toEqual([2]);
    expect(saved.activeId()).toBeNull();
  });
});
