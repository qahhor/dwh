// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTRatingComponent } from './rating.component';
import { SMTRatingValueAccessor } from './rating-value-accessor';

@Component({
  standalone: true,
  imports: [SMTRatingComponent, SMTRatingValueAccessor, FormsModule],
  template: `
    <smt-rating smtAriaLabel="Service" [(value)]="stars" clearable />
    <smt-rating [smtMax]="3" [(ngModel)]="score" [disabled]="off()" />
  `,
})
class Host {
  stars: number | null = 2;
  score: number | null = null;
  readonly off = signal(false);
}

describe('SMTRatingComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const [first, second] = Array.from(fixture.nativeElement.querySelectorAll('[role="radiogroup"]')) as HTMLElement[];
    const stars = (group: HTMLElement) => Array.from(group.querySelectorAll('[role="radio"]')) as HTMLButtonElement[];
    return { fixture, first, second, stars, settle };
  }

  it('is a named radio group of stars, the chosen one being the only tab stop', async () => {
    const { first, stars } = await render();
    expect(first.getAttribute('aria-label')).toBe('Service');
    expect(stars(first).map(star => star.getAttribute('aria-label'))).toEqual(['1 of 5', '2 of 5', '3 of 5', '4 of 5', '5 of 5']);
    expect(stars(first).map(star => star.getAttribute('aria-checked'))).toEqual(['false', 'true', 'false', 'false', 'false']);
    expect(stars(first).map(star => star.tabIndex)).toEqual([-1, 0, -1, -1, -1]);
  });

  it('moves and chooses with the arrows and the ends, and clears a clearable rating by its own star', async () => {
    const { fixture, first, stars, settle } = await render();
    stars(first)[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    await settle();
    expect(fixture.componentInstance.stars).toBe(3);
    expect(document.activeElement).toBe(stars(first)[2]);
    stars(first)[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true }));
    await settle();
    expect(fixture.componentInstance.stars).toBe(5);
    stars(first)[4].click();
    await settle();
    expect(fixture.componentInstance.stars).toBeNull();
  });

  it('writes to ngModel, names stars by its own maximum and follows the disabled state', async () => {
    const { fixture, second, stars, settle } = await render();
    expect(stars(second).map(star => star.getAttribute('aria-label'))).toEqual(['1 of 3', '2 of 3', '3 of 3']);
    expect(second.getAttribute('aria-label')).toBe('Rating');
    stars(second)[2].click();
    await settle();
    expect(fixture.componentInstance.score).toBe(3);
    fixture.componentInstance.off.set(true);
    await settle();
    await settle();
    expect(stars(second)[0].disabled).toBe(true);
  });
});
