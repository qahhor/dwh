/* Not vendored: tests for the sort glyph drawn here (ADR-0015 rule 2). */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMTSortIconComponent } from './sort-icon.component';

function render(sort: 'ASC' | 'DESC' | undefined) {
  const fixture = TestBed.createComponent(SMTSortIconComponent);
  fixture.componentRef.setInput('smtSort', sort);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, glyph: () => host.querySelector('.material-symbols-outlined')?.textContent?.trim() };
}

describe('SMTSortIconComponent', () => {
  it('draws the direction of the sort as its own glyph', () => {
    expect(render('ASC').glyph()).toBe('arrow_upward');
    expect(render('DESC').glyph()).toBe('arrow_downward');
    expect(render(undefined).glyph()).toBe('swap_vert');
  });

  it('follows a change of direction and exposes it on the host', () => {
    const { fixture, host, glyph } = render(undefined);
    expect(host.hasAttribute('data-sort')).toBe(false);

    fixture.componentRef.setInput('smtSort', 'DESC');
    fixture.detectChanges();
    expect(glyph()).toBe('arrow_downward');
    expect(host.getAttribute('data-sort')).toBe('DESC');
  });

  it('is decorative, since the column header carries aria-sort', () => {
    expect(render('ASC').host.getAttribute('aria-hidden')).toBe('true');
  });
});
