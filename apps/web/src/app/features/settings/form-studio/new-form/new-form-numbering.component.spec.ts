import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { NewFormNumberingComponent } from './new-form-numbering.component';
import { initialWorkflow } from './new-form-workflow.component';

const LABELS = { ru: 'Метка', uz: 'Yorliq', en: 'Label' };

describe('NewFormNumberingComponent', () => {
  it('numbers a document and gives the number on a status', async () => {
    await TestBed.configureTestingModule({ imports: [NewFormNumberingComponent] }).compileComponents();
    const fixture = TestBed.createComponent(NewFormNumberingComponent);
    fixture.componentRef.setInput('states', initialWorkflow(() => LABELS).states);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    const component = fixture.componentInstance;
    component.toggle(true);
    const numbering = emit.mock.lastCall?.[0];
    expect(numbering).toEqual({ pattern: 'DOC-{YYYY}-{NNNNNN}', reset: 'year' });
    fixture.componentRef.setInput('numbering', numbering);
    fixture.detectChanges();
    expect(component.example()).toMatch(/^DOC-\d{4}-000001$/);
    expect(component.assignOptions().map((option) => option.id)).toEqual([
      '',
      'draft',
      'pending',
      'approved',
      'rejected',
    ]);
    expect(component.resetOptions()).toHaveLength(3);
    component.setReset('month');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ reset: 'month' });
    component.setReset('week');
    component.setAssignOn('approved');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ assignOn: 'approved' });
    component.setAssignOn('');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('assignOn');
    component.patch({ pattern: 'A-{NN}' });
    expect(emit.mock.lastCall?.[0]).toMatchObject({ pattern: 'A-{NN}' });
    component.toggle(false);
    expect(emit.mock.lastCall?.[0]).toBeUndefined();
    fixture.componentRef.setInput('locked', true);
    const calls = emit.mock.calls.length;
    component.toggle(true);
    expect(emit.mock.calls.length).toBe(calls);
    expect(component.text(undefined)).toBe('');
  });
});
