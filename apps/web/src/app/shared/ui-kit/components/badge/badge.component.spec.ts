/* Not vendored: tests for the badge as the application uses it (ADR-0015 rule 2). */
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMTBadgeComponent, type TBadgeVariant } from './badge.component';

@Component({
  imports: [SMTBadgeComponent],
  template: `
    <smt-badge
      class="status"
      smtSize="SM"
      [smtVariant]="variant()"
      [smtAppearance]="appearance()"
      [smtLabel]="label()"
      [smtLeftIcon]="icon()"
      [smtHasDot]="dot()"
      (smtIconClicked)="clicks.push($event.side)"
      >{{ content() }}</smt-badge
    >
  `,
})
class HostComponent {
  readonly variant = signal<TBadgeVariant>('success');
  readonly appearance = signal<'light' | 'dark'>('light');
  readonly label = signal<string | undefined>(undefined);
  readonly icon = signal<string | null>(null);
  readonly dot = signal(false);
  readonly content = signal('');
  readonly clicks: string[] = [];
}

function render(setup: (host: HostComponent) => void = () => undefined) {
  const fixture = TestBed.createComponent(HostComponent);
  setup(fixture.componentInstance);
  fixture.detectChanges();
  const badge = () => fixture.nativeElement.querySelector('smt-badge') as HTMLElement;
  const update = (change: (host: HostComponent) => void) => {
    change(fixture.componentInstance);
    fixture.detectChanges();
  };
  return { fixture, badge, update };
}

describe('SMTBadgeComponent', () => {
  it('shows its label followed by the projected content, keeping the classes its parent gives it', () => {
    const { badge } = render((host) => {
      host.label.set('2FA');
      host.content.set('включена');
    });

    expect(badge().textContent?.replace(/\s+/g, ' ').trim()).toBe('2FA включена');
    expect(badge().classList).toContain('status');
  });

  it('maps each variant to its theme colours, muted on dark surfaces', () => {
    const { badge, update } = render((host) => host.content.set('Активен'));
    const expected: [TBadgeVariant, string, string][] = [
      ['success', 'bg-success-50', 'text-success-700'],
      ['error', 'bg-error-50', 'text-error-700'],
      ['warning', 'bg-warning-50', 'text-warning-700'],
      ['blue', 'bg-blue-50', 'text-blue-700'],
      ['gray', 'bg-gray-50', 'text-gray-700'],
    ];
    for (const [variant, background, text] of expected) {
      update((host) => host.variant.set(variant));
      expect(badge().classList).toContain(background);
      expect(badge().classList).toContain(text);
    }
    expect(badge().classList).not.toContain('bg-success-50');

    update((host) => {
      host.variant.set('success');
      host.appearance.set('dark');
    });
    expect(badge().classList).toContain('bg-success-950');
    expect(badge().classList).not.toContain('bg-success-50');
  });

  it('is an icon-only badge only when it has an icon and no label', () => {
    const { badge, update } = render((host) => host.icon.set('verified_user'));
    expect(badge().classList).toContain('rounded-full');
    expect(badge().querySelector('smt-icon')?.textContent?.trim()).toBe('verified_user');

    update((host) => host.label.set('Подтверждён'));
    expect(badge().classList).not.toContain('rounded-full');
    expect(badge().classList).toContain('rounded-2xl');
  });

  it('is a text badge when an icon comes with projected content, and icon-only again once the content goes', async () => {
    const { fixture, badge, update } = render((host) => {
      host.icon.set('verified_user');
      host.content.set('Подтверждён');
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(badge().classList).not.toContain('rounded-full');
    expect(badge().textContent).toContain('Подтверждён');

    update((host) => host.content.set(''));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(badge().classList).toContain('rounded-full');
  });

  it('is a text badge, not an icon-only one, when it holds only projected content', () => {
    const { badge } = render((host) => host.content.set('Черновик'));

    expect(badge().classList).not.toContain('rounded-full');
    expect(badge().classList).toContain('rounded-2xl');
    expect(badge().textContent?.trim()).toBe('Черновик');
  });

  it('shows a dot in the colour of its variant on request', () => {
    const { badge, update } = render((host) => host.content.set('Готово'));
    expect(badge().querySelector('.rounded-full')).toBeNull();

    update((host) => {
      host.dot.set(true);
      host.variant.set('error');
    });
    expect(badge().querySelector('.bg-error-500')).not.toBeNull();
  });

  it('reports a click on its icon with the side it sits on', () => {
    const { fixture, badge } = render((host) => {
      host.icon.set('close');
      host.label.set('Фильтр');
    });

    (badge().querySelector('smt-icon') as HTMLElement).click();
    expect(fixture.componentInstance.clicks).toEqual(['left']);
  });
});
