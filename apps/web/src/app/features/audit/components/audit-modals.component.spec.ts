// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { tickInZone } from '../../../shared/ui-kit/testing/zone-tick';
import { inScreen } from '../../../../testing/in-screen';
import { AuditRecord } from '../audit.models';
import { AuditModalsComponent } from './audit-modals.component';

const RECORD = {
  id: 1,
  tableName: 'ms_tasks',
  rowPk: '42',
  event: 'U',
  changedAt: '2026-09-26T10:00:00Z',
  changedBy: 1,
  changedByName: 'Ann',
  changedByLogin: 'ann',
  oldRow: { title: 'Old title', priority: 'low' },
  newRow: { title: 'New title', priority: 'low', due: '2026-10-01' },
  changedColumns: ['title', 'due'],
} as unknown as AuditRecord;

@Component({
  standalone: true,
  imports: [AuditModalsComponent],
  template: `<app-audit-modals [selectedAudit]="audit()" (closeAuditModal)="audit.set(null)" />`,
})
class Host {
  readonly audit = signal<AuditRecord | null>(null);
}

describe('AuditModalsComponent diff', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('lists every changed field with its value before and after in the kit table', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    fixture.componentInstance.audit.set(RECORD);
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();

    const screen = inScreen(fixture.nativeElement);
    const table = screen.querySelector('[role="dialog"] smt-table [role="table"], [role="dialog"] smt-table table') as HTMLElement | null;
    expect(table?.getAttribute('aria-label')).toBe('Сравнение значений до и после изменения');
    const fields = Array.from(screen.querySelectorAll('.field-name') as Element[]).map(node => node.textContent?.trim());
    expect(fields).toEqual(['title', 'priority', 'due']);
    const before = Array.from(screen.querySelectorAll('.diff-val--before') as Element[]).map(node => node.textContent);
    const after = Array.from(screen.querySelectorAll('.diff-val--after') as Element[]).map(node => node.textContent);
    expect(before).toEqual(['Old title', 'low', '—']);
    expect(after).toEqual(['New title', 'low', '2026-10-01']);
  });
});
