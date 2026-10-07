import { Component, input, output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { RolesApi } from '@core/services/roles.api';
import type { EntityRecord } from '@shared/entity/entities.api';
import { UserAccessTabComponent } from './user-access-tab.component';

@Component({ selector: 'app-user-org-units-panel', template: '' })
class UnitsPanelStub {
  readonly userId = input<number>();
  readonly revision = input<number | undefined>();
  readonly revisionChange = output<number>();
}

@Component({ selector: 'app-user-effective-permissions-panel', template: '' })
class RightsPanelStub {
  readonly userId = input<number>();
  readonly revision = input<number | undefined>();
  readonly canAssign = input(false);
  readonly userRoleNames = input<string[]>([]);
  readonly revisionChange = output<number>();
}

/* The tab "Roles and rights" of a user (ADR-0032 7.2): each part only for holders of its right. */
describe('UserAccessTabComponent', () => {
  const granted = signal(new Set<string>());
  const roles = {
    list: () =>
      of([
        { id: 1, name: 'Администратор' },
        { id: 2, name: 'Оператор' },
        { id: 3, name: 'Аудитор' },
      ]),
  };

  beforeEach(() => {
    granted.set(new Set());
    TestBed.configureTestingModule({
      providers: [
        {
          provide: PermissionService,
          useValue: { hasPermission: (f: string, a: string) => granted().has(`${f}:${a}`) },
        },
        { provide: RolesApi, useValue: roles },
      ],
    });
    TestBed.overrideComponent(UserAccessTabComponent, { set: { imports: [UnitsPanelStub, RightsPanelStub] } });
  });

  async function render(...rights: string[]) {
    granted.set(new Set(rights));
    const fixture = TestBed.createComponent(UserAccessTabComponent);
    fixture.componentRef.setInput('record', { id: 9, revision: 4, roleIds: [2, 3] } as unknown as EntityRecord);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return { fixture, tab: fixture.componentInstance, host };
  }

  it('shows neither part without a right', async () => {
    const { host } = await render();
    expect(host.querySelector('app-user-org-units-panel')).toBeNull();
    expect(host.querySelector('app-user-effective-permissions-panel')).toBeNull();
  });

  it('gives each part the user and its revision', async () => {
    const { fixture, host } = await render('md.org_units:view', 'md.assignments:view', 'md.assignments:assign');
    const units = fixture.debugElement.query((node) => node.name === 'app-user-org-units-panel')
      .componentInstance as UnitsPanelStub;
    const rights = fixture.debugElement.query((node) => node.name === 'app-user-effective-permissions-panel')
      .componentInstance as RightsPanelStub;

    expect(host.querySelector('[data-testid="user-access-tab"]')).not.toBeNull();
    expect(units.userId()).toBe(9);
    expect(units.revision()).toBe(4);
    expect(rights.canAssign()).toBe(true);

    units.revisionChange.emit(5);
    fixture.detectChanges();
    expect(rights.revision()).toBe(5);
  });

  it('names the user roles only for a holder of the roles right', async () => {
    const { fixture, tab } = await render('md.assignments:view');
    expect(tab.roleNames()).toEqual([]);

    granted.update((rights) => new Set([...rights, 'md.roles:view']));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(tab.roleNames()).toEqual(['Оператор', 'Аудитор']);
  });
});
