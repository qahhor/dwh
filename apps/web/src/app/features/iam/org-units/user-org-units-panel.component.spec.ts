import { TestBed } from '@angular/core/testing';
import { Component, viewChild } from '@angular/core';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { OrgUnitsApiService } from './org-units-api.service';
import { OrgUnit, ScopeRule, UserAssignments, UserScope } from './org-units.models';
import { UserOrgUnitsPanelComponent } from './user-org-units-panel.component';

const unit = (id: number, parentId: number | null, code: string, name: string, state: OrgUnit['state'] = 'A') =>
  ({
    id,
    parentId,
    code,
    name,
    kind: parentId ? 'branch' : 'company',
    state,
    orderNo: 0,
    createdAt: '',
    modifiedAt: '',
  }) as OrgUnit;
const units: OrgUnit[] = [
  unit(7, null, 'HQ', 'Headquarters'),
  unit(8, 7, 'ACTIVE', 'Active child'),
  { ...unit(9, 7, 'LEGACY', 'Legacy branch', 'P'), orderNo: 1 },
];

@Component({
  imports: [UserOrgUnitsPanelComponent],
  template: `<app-user-org-units-panel [userId]="selectedUserId" />`,
})
class UserPanelHost {
  selectedUserId = 42;
  readonly panel = viewChild.required(UserOrgUnitsPanelComponent);
  requestTarget(userId: number): void {
    const decision = this.panel().canLeave();
    if (typeof decision === 'boolean') {
      if (decision) this.selectedUserId = userId;
    } else
      decision.subscribe((allow) => {
        if (allow) this.selectedUserId = userId;
      });
  }
}

describe('UserOrgUnitsPanelComponent', () => {
  function setup(
    options: {
      writable?: boolean;
      target?: number;
      assigned?: number[];
      legacy?: number | null;
      visible?: number[];
      rule?: ScopeRule;
      /** User 43 holds unit 9 and user 42 unit 7, each answered for the user asked. */
      perUser?: boolean;
    } = {},
  ) {
    const api = {
      list: vi.fn((): Observable<OrgUnit[]> => of(units)),
      assignments: vi.fn((userId: number): Observable<UserAssignments> =>
        options.perUser
          ? of({ userId, orgUnitIds: userId === 43 ? [9] : [7], legacyOrgUnitId: null, revision: 4 })
          : of({
              userId: options.target ?? 42,
              orgUnitIds: options.assigned ?? [7],
              legacyOrgUnitId: options.legacy === undefined ? 9 : options.legacy,
              revision: 4,
            }),
      ),
      scope: vi.fn((userId: number): Observable<UserScope> =>
        options.perUser
          ? of({ rule: 'UNITS', visibleOrgUnitIds: userId === 43 ? [9] : [7] })
          : of({ rule: options.rule ?? 'SUBTREE', visibleOrgUnitIds: options.visible ?? [7, 8] }),
      ),
      saveAssignments: vi.fn((_userId: number, _orgUnitIds: number[], _revision?: number): Observable<undefined> =>
        of(undefined),
      ),
    };
    const toast = { success: vi.fn(), error: vi.fn(), show: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: OrgUnitsApiService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    const permissions = TestBed.inject(PermissionService);
    permissions.setPermissions(
      options.writable === false ? ['md.org_units.view'] : ['md.org_units.view', 'md.org_units.assign'],
    );
    /** Renders the panel for `target` once the test has set its answers. */
    const render = () => {
      const fixture = TestBed.createComponent(UserOrgUnitsPanelComponent);
      fixture.componentRef.setInput('userId', options.target ?? 42);
      fixture.detectChanges();
      return { fixture, panel: fixture.componentInstance, text: () => fixture.nativeElement.textContent as string };
    };
    return { api, toast, permissions, render };
  }
  /** The panel rendered at once, as most tests need it. */
  function rendered(options: Parameters<typeof setup>[0] = {}) {
    const { render, ...rest } = setup(options);
    return { ...render(), ...rest };
  }
  const box = (fixture: { nativeElement: HTMLElement }, id: number) =>
    fixture.nativeElement.querySelector(`input[data-smt-check="${id}"]`) as HTMLInputElement;

  it('saves from the newest revision of the user it knows and hands the raised one to the host', () => {
    const { fixture, panel, api } = rendered();
    const raised: number[] = [];
    panel.revisionChange.subscribe((revision) => raised.push(revision));
    fixture.componentRef.setInput('revision', 2);
    fixture.detectChanges();

    panel.toggleAssignment(units[0]);
    panel.save();

    expect(api.saveAssignments).toHaveBeenCalledWith(42, expect.any(Array), 4);
    expect(raised).toEqual([5]);
  });

  it('offers a reload once when the user moved on, drops the draft and asks the host to read the user again', () => {
    const { fixture, panel, api, toast } = rendered();
    const stale = vi.fn();
    panel.staleUser.subscribe(stale);
    api.saveAssignments.mockReturnValueOnce(throwError(() => ({ status: 428, code: 'precondition_required' })));

    panel.toggleAssignment(units[0]);
    panel.save();
    fixture.detectChanges();

    expect(panel.saveError()).toBeNull();
    expect(toast.show).toHaveBeenCalledOnce();
    (toast.show.mock.calls[0][4] as { run: () => void }).run();
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
    expect(api.assignments).toHaveBeenCalledTimes(2);
    expect(stale).toHaveBeenCalledOnce();
  });

  it('keeps assigned, effective and legacy organization IDs separate and never writes an unchanged draft', () => {
    const { fixture, panel, api, text } = rendered();
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
    expect(box(fixture, 7)?.checked).toBe(true);
    expect(box(fixture, 8)?.checked).toBe(false);
    // The row itself states the choice to assistive technology.
    const grid = fixture.nativeElement.querySelector('[role="treegrid"][aria-multiselectable="true"]') as HTMLElement;
    expect(grid.querySelector('[data-smt-row-id="7"]')?.getAttribute('aria-selected')).toBe('true');
    expect(grid.querySelector('[data-smt-row-id="8"]')?.getAttribute('aria-selected')).toBe('false');
    expect(text()).toContain('Legacy branch');
    expect(text()).toContain('Active child');
    panel.save();
    expect(api.saveAssignments).not.toHaveBeenCalled();
  });

  it.each([
    ['ALL', 'не ограничивает данные оргструктурой'],
    ['SELF', 'не привязано к дереву подразделений'],
  ] as const)('explains an empty %s effective scope semantically', (rule, explanation) => {
    const { text } = rendered({ rule, visible: [], assigned: [] });
    expect(text()).toContain(explanation);
    expect(text()).not.toContain('Нет доступа');
  });

  it('supports explicit empty replacement, warns about inactive assignments and leaves legacy context unchanged', () => {
    const { fixture, panel, api, text } = rendered({ assigned: [9] });
    expect(text()).toContain('неактив');
    panel.toggleAssignment(units[2]);
    fixture.detectChanges();
    expect(panel.selectedOrgUnitIds()).toEqual([]);
    panel.save();
    expect(api.saveAssignments).toHaveBeenCalledWith(42, [], 4);
    expect(panel.legacyOrgUnitId()).toBe(9);
  });

  it('renders a successful snapshot read-only without assign permission', () => {
    const { fixture, panel, api } = rendered({ writable: false });
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
    expect(box(fixture, 7)?.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-action="save-assignments"]')).toBeNull();
    panel.toggleAssignment(units[1]);
    panel.save();
    expect(api.saveAssignments).not.toHaveBeenCalled();
  });

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('does not read or mutate an unsafe user target %s', (target) => {
    const { panel, api } = rendered({ target });
    panel.save();
    expect(api.list).not.toHaveBeenCalled();
    expect(api.assignments).not.toHaveBeenCalled();
    expect(api.scope).not.toHaveBeenCalled();
    expect(api.saveAssignments).not.toHaveBeenCalled();
  });

  it('rejects an imprecise assignment ID before mutation', () => {
    const { panel, api } = rendered();
    panel.selectedOrgUnitIds.set([7, Number.MAX_SAFE_INTEGER + 1]);
    panel.save();
    expect(api.saveAssignments).not.toHaveBeenCalled();
  });

  it('drops stale target reads and cancels all reads on destruction', () => {
    const { api, render } = setup();
    const reads = () => ({
      tree: new Subject<OrgUnit[]>(),
      assignments: new Subject<UserAssignments>(),
      scope: new Subject<UserScope>(),
    });
    const expectObserved = (subjects: ReturnType<typeof reads>, observed: boolean) =>
      expect([subjects.tree.observed, subjects.assignments.observed, subjects.scope.observed]).toEqual([
        observed,
        observed,
        observed,
      ]);
    const first = reads();
    api.list.mockReturnValueOnce(first.tree);
    api.assignments
      .mockReturnValueOnce(first.assignments)
      .mockReturnValue(of({ userId: 43, orgUnitIds: [8], legacyOrgUnitId: null }));
    api.scope.mockReturnValueOnce(first.scope).mockReturnValue(of({ rule: 'UNITS', visibleOrgUnitIds: [8] }));
    const { fixture, panel } = render();
    fixture.componentRef.setInput('userId', 43);
    fixture.detectChanges();
    expectObserved(first, false);
    first.assignments.next({ userId: 42, orgUnitIds: [7], legacyOrgUnitId: 9 });
    expect(panel.selectedOrgUnitIds()).toEqual([8]);

    const last = reads();
    api.list.mockReturnValueOnce(last.tree);
    api.assignments.mockReturnValueOnce(last.assignments);
    api.scope.mockReturnValueOnce(last.scope);
    panel.reloadTree();
    panel.reloadAssignments();
    panel.reloadScope();
    expectObserved(last, true);
    fixture.destroy();
    expectObserved(last, false);
  });

  it('keeps dirty state through 409, blocks double submit while pending and permits an explicit retry', () => {
    const { fixture, panel, api, text } = rendered();
    panel.toggleAssignment(units[1]);
    const write = new Subject<undefined>();
    api.saveAssignments.mockReturnValueOnce(write);
    const busy = vi.fn();
    panel.busyChange.subscribe(busy);
    panel.save();
    panel.save();
    expect(api.saveAssignments).toHaveBeenCalledTimes(1);
    expect(panel.canLeave()).toBe(false);
    const unload = new Event('beforeunload', { cancelable: true });
    panel.beforeUnload(unload as BeforeUnloadEvent);
    expect(unload.defaultPrevented).toBe(true);
    write.error({ status: 409, code: 'CONFLICT', title: 'Conflict', detail: 'Assignments changed elsewhere' });
    fixture.detectChanges();
    expect(panel.selectedOrgUnitIds()).toEqual([7, 8]);
    expect(text()).toContain('Assignments changed elsewhere');
    panel.save();
    expect(api.saveAssignments).toHaveBeenCalledTimes(2);
    expect(busy.mock.calls.map((call) => call[0])).toEqual([true, false, true, false]);
  });

  it('shows 403 read failure with retry and never enables save before a successful assignment GET', () => {
    const { fixture, panel, api, text } = rendered();
    api.assignments
      .mockReturnValueOnce(throwError(() => ({ status: 403, detail: 'Forbidden assignments' })))
      .mockReturnValueOnce(of({ userId: 42, orgUnitIds: [8], legacyOrgUnitId: null }));
    panel.reloadAssignments();
    fixture.detectChanges();
    expect(text()).toContain('Forbidden assignments');
    panel.save();
    expect(api.saveAssignments).not.toHaveBeenCalled();
    panel.reloadAssignments();
    fixture.detectChanges();
    expect(panel.selectedOrgUnitIds()).toEqual([8]);
  });

  it('keeps assignment and scope snapshots usable when only the tree read fails and retries that read alone', () => {
    const { fixture, panel, api, text } = rendered();
    api.list
      .mockReturnValueOnce(throwError(() => ({ status: 500, detail: 'Tree only failed' })))
      .mockReturnValueOnce(of(units));
    panel.reloadTree();
    fixture.detectChanges();
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
    expect(panel.effectiveScope()?.visibleOrgUnitIds).toEqual([7, 8]);
    expect(text()).toContain('Tree only failed');
    const retry = fixture.nativeElement.querySelector(
      'button[data-action="retry-assignment-tree"]',
    ) as HTMLButtonElement;
    expect(retry).not.toBeNull();
    retry.click();
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(3);
    expect(api.assignments).toHaveBeenCalledTimes(1);
    expect(api.scope).toHaveBeenCalledTimes(1);
  });

  it('does not report a valid assignment missing until a successful tree snapshot is authoritative', () => {
    const { api, render } = setup({ assigned: [8], legacy: null, rule: 'UNITS', visible: [8] });
    const tree = new Subject<OrgUnit[]>();
    api.list.mockReturnValueOnce(tree);
    const { fixture, panel, text } = render();

    expect(panel.assignmentsLoaded()).toBe(true);
    expect(panel.treeLoaded()).toBe(false);
    expect(panel.unresolvedAssignmentIds).toEqual([]);
    expect(text()).not.toContain('отсутствуют в загруженном дереве');

    tree.error({ status: 503, detail: 'Tree unavailable' });
    fixture.detectChanges();
    expect(panel.unresolvedAssignmentIds).toEqual([]);
    expect(text()).not.toContain('отсутствуют в загруженном дереве');

    panel.reloadTree();
    fixture.detectChanges();
    expect(panel.treeLoaded()).toBe(true);
    expect(panel.unresolvedAssignmentIds).toEqual([]);
  });

  it('clears protected data and discard decisions when view is revoked while assign remains', () => {
    const { fixture, panel, api, permissions, text } = rendered();
    panel.toggleAssignment(units[1]);
    const decision = vi.fn();
    (panel.canLeave() as Observable<boolean>).subscribe(decision);
    fixture.detectChanges();
    expect(panel.discard.open()).toBe(true);
    permissions.setPermissions(['md.org_units.assign']);
    panel.save();
    fixture.detectChanges();
    expect(api.saveAssignments).not.toHaveBeenCalled();
    expect(panel.discard.open()).toBe(false);
    expect(decision).toHaveBeenCalledWith(false);
    expect(panel.units()).toEqual([]);
    expect(panel.selectedOrgUnitIds()).toEqual([]);
    expect(panel.effectiveScope()).toBeNull();
    expect(text()).not.toContain('Headquarters');
  });

  it('owns an issued write through view revocation but never restores protected state from its late result', () => {
    const { fixture, panel, api, toast, permissions } = rendered();
    panel.toggleAssignment(units[1]);
    const write = new Subject<undefined>();
    api.saveAssignments.mockReturnValueOnce(write);
    panel.save();
    permissions.setPermissions(['md.org_units.assign']);
    fixture.detectChanges();
    expect(panel.pending()).toBe(true);
    expect(write.observed).toBe(true);
    expect(panel.canLeave()).toBe(false);
    permissions.setPermissions(['md.org_units.view', 'md.org_units.assign']);
    fixture.detectChanges();
    write.next(undefined);
    fixture.detectChanges();
    expect(panel.pending()).toBe(false);
    expect(panel.units()).toEqual([]);
    expect(panel.selectedOrgUnitIds()).toEqual([]);
    expect(toast.success).not.toHaveBeenCalled();
    expect(api.scope).toHaveBeenCalledTimes(1);
  });

  it('queues a target changed during a write and never applies the old result under the new input', () => {
    const { fixture, panel, api, toast } = rendered({ perUser: true });
    panel.toggleAssignment(units[1]);
    const write = new Subject<undefined>();
    api.saveAssignments.mockReturnValueOnce(write);
    panel.save();
    fixture.componentRef.setInput('userId', 43);
    fixture.detectChanges();
    expect(panel.assignmentsLoaded()).toBe(false);
    expect(panel.pending()).toBe(true);

    write.next(undefined);
    fixture.detectChanges();
    expect(toast.success).not.toHaveBeenCalled();
    expect(panel.selectedOrgUnitIds()).toEqual([9]);
    expect(api.assignments.mock.calls.map((call) => call[0])).toEqual([42, 43]);
    expect(api.scope.mock.calls.map((call) => call[0])).toEqual([42, 43]);
  });

  it('invalidates an old pending epoch even when the forced input changes away and back', () => {
    const { fixture, panel, api, toast, text } = rendered({ perUser: true });
    panel.toggleAssignment(units[1]);
    const write = new Subject<undefined>();
    api.saveAssignments.mockReturnValueOnce(write);
    panel.save();
    fixture.componentRef.setInput('userId', 43);
    fixture.detectChanges();
    fixture.componentRef.setInput('userId', 42);
    fixture.detectChanges();
    write.error({ status: 409, detail: 'Old target failure' });
    fixture.detectChanges();
    expect(toast.success).not.toHaveBeenCalled();
    expect(panel.saveError()).toBeNull();
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
    expect(api.assignments.mock.calls.map((call) => call[0])).toEqual([42, 42]);
    expect(text()).not.toContain('Old target failure');
  });

  it('fails closed on a forced dirty input replacement instead of retaining the old draft under the new target', () => {
    const { fixture, panel, api } = rendered({ perUser: true });
    panel.toggleAssignment(units[1]);
    fixture.detectChanges();
    fixture.componentRef.setInput('userId', 43);
    fixture.detectChanges();
    expect(panel.discard.open()).toBe(false);
    expect(panel.selectedOrgUnitIds()).toEqual([9]);
    expect(box(fixture, 7).checked).toBe(false);
    expect(box(fixture, 9).checked).toBe(true);
    expect(api.assignments.mock.calls.map((call) => call[0])).toEqual([42, 43]);
  });

  it('lets a host cancel a dirty target change before committing the public input', () => {
    const { api } = setup({ perUser: true });
    const fixture = TestBed.createComponent(UserPanelHost);
    fixture.detectChanges();
    const host = fixture.componentInstance;
    host.panel().toggleAssignment(units[1]);
    host.requestTarget(43);
    fixture.detectChanges();
    expect(host.selectedUserId).toBe(42);
    expect(host.panel().discard.open()).toBe(true);
    host.panel().discard.cancel();
    fixture.detectChanges();
    expect(host.selectedUserId).toBe(42);
    expect(host.panel().selectedOrgUnitIds()).toEqual([7, 8]);
    expect(api.assignments).toHaveBeenCalledTimes(1);
    host.requestTarget(43);
    host.panel().discard.confirm();
    fixture.detectChanges();
    expect(host.selectedUserId).toBe(43);
    expect(host.panel().selectedOrgUnitIds()).toEqual([9]);
  });

  it('marks a completed save clean before isolated effective-scope refresh failure', () => {
    const { fixture, panel, api, toast, text } = rendered();
    panel.toggleAssignment(units[1]);
    api.scope.mockReturnValueOnce(throwError(() => ({ status: 500, detail: 'Scope refresh failed' })));
    panel.save();
    fixture.detectChanges();
    expect(toast.success).toHaveBeenCalledTimes(1);
    expect(panel.hasUnsavedWork()).toBe(false);
    expect(text()).toContain('Сохранено');
    panel.save();
    expect(api.saveAssignments).toHaveBeenCalledTimes(1);
    expect(api.assignments).toHaveBeenCalledTimes(1);
    expect(api.scope).toHaveBeenCalledTimes(2);
  });

  it('requires confirmation to discard dirty edits and cancellation keeps them', () => {
    const { panel } = rendered();
    panel.toggleAssignment(units[1]);
    const result = vi.fn();
    (panel.canLeave() as Observable<boolean>).subscribe(result);
    expect(panel.discard.open()).toBe(true);
    panel.discard.cancel();
    expect(result).toHaveBeenCalledWith(false);
    expect(panel.selectedOrgUnitIds()).toEqual([7, 8]);
    (panel.canLeave() as Observable<boolean>).subscribe(result);
    panel.discard.confirm();
    expect(result).toHaveBeenCalledWith(true);
    expect(panel.selectedOrgUnitIds()).toEqual([7]);
  });
});
