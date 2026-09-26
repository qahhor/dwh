// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTCheckboxComponent } from './checkbox.component';
import { SMTCheckboxValueAccessor } from './checkbox-value-accessor';

@Component({
  standalone: true,
  imports: [SMTCheckboxComponent, SMTCheckboxValueAccessor, FormsModule],
  template: `<div smt-checkbox name="required" [(ngModel)]="required" [disabled]="locked()">Required field</div>`,
})
class Host {
  required = true;
  readonly locked = signal(false);
}

describe('SMTCheckboxValueAccessor', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const box = fixture.nativeElement.querySelector('[role="checkbox"]') as HTMLElement;
    return { fixture, box, settle };
  }

  it('shows the ngModel value and writes a click back to it', async () => {
    const { fixture, box, settle } = await render();
    expect(box.getAttribute('aria-checked')).toBe('true');
    box.click();
    await settle();
    expect(fixture.componentInstance.required).toBe(false);
    expect(box.getAttribute('aria-checked')).toBe('false');
  });

  it('follows the disabled state ngModel gives it', async () => {
    const { fixture, box, settle } = await render();
    fixture.componentInstance.locked.set(true);
    await settle();
    await settle();
    expect(box.getAttribute('aria-disabled')).toBe('true');
    box.click();
    await settle();
    expect(fixture.componentInstance.required).toBe(true);
  });
});
