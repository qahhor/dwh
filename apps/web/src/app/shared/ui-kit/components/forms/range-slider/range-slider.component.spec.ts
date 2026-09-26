// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { redraw } from '../../../../../../testing/in-screen';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTRange, SMTRangeSliderComponent } from './range-slider.component';

@Component({
  standalone: true,
  imports: [SMTRangeSliderComponent],
  template: `<smt-range-slider smtAriaLabel="Days" smtSuffix=" d" [smtMin]="0" [smtMax]="90" [smtStep]="5" [(value)]="band" />`,
})
class Host {
  band: SMTRange | null = { from: 10, to: 40 };
}

describe('SMTRangeSliderComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const [from, to] = Array.from(element.querySelectorAll('input[type="range"]')) as HTMLInputElement[];
    return { fixture, element, from, to, settle };
  }

  it('is a named group of two real sliders with their bounds, step and spoken values', async () => {
    const { element, from, to } = await render();
    expect(element.querySelector('smt-range-slider')!.getAttribute('role')).toBe('group');
    expect(from.getAttribute('aria-label')).toBe('Days: From');
    expect(to.getAttribute('aria-label')).toBe('Days: To');
    expect([from.min, from.max, from.step]).toEqual(['0', '90', '5']);
    expect(from.value).toBe('10');
    expect(to.value).toBe('40');
    expect(from.getAttribute('aria-valuetext')).toBe('10 d');
    expect(element.querySelector('.smt-range-slider__values')!.textContent).toBe('10 d – 40 d');
  });

  it('moves each end and stops one thumb at the other', async () => {
    const { fixture, from, to, settle } = await render();
    from.value = '25';
    from.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.band).toEqual({ from: 25, to: 40 });
    from.value = '60';
    from.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.band).toEqual({ from: 40, to: 40 });
    expect(from.value).toBe('40');
    to.value = '85';
    to.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.band).toEqual({ from: 40, to: 85 });
  });

  it('shows the whole range while there is no value', async () => {
    const { fixture, from, to, settle } = await render();
    fixture.componentInstance.band = null;
    redraw(fixture);
    await settle();
    await settle();
    expect([from.value, to.value]).toEqual(['0', '90']);
  });
});
