import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { OrgUnitTreeComponent } from './org-unit-tree.component';

describe('OrgUnitTreeComponent', () => {
  it('uses nested lists with separate named expand and select buttons', () => {
    const fixture = TestBed.createComponent(OrgUnitTreeComponent);
    fixture.componentRef.setInput('units', [
      { id: 1, parentId: null, code: 'ROOT', name: 'Root', kind: 'future-kind', state: 'A', orderNo: 0 },
      { id: 2, parentId: 1, code: 'CHILD', name: 'Child', kind: 'branch', state: 'P', orderNo: 0 }
    ]);
    const selected = vi.fn(); fixture.componentInstance.selectUnit.subscribe(selected);
    fixture.detectChanges();
    const expand = fixture.nativeElement.querySelector('button[aria-expanded]') as HTMLButtonElement;
    expect(expand?.getAttribute('aria-label') ?? '').toContain('Root');
    expect(fixture.nativeElement.querySelector('ul ul')).not.toBeNull();
    const select = fixture.nativeElement.querySelector('[data-select="2"]') as HTMLButtonElement;
    select.click(); expect(selected).toHaveBeenCalledWith(expect.objectContaining({ id: 2 }));
    expand.click(); fixture.detectChanges();
    expect(expand.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('[data-select="2"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('future-kind');
  });
});
