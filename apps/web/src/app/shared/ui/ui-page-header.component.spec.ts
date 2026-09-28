import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiPageHeaderComponent } from './ui-page-header.component';

@Component({
  imports: [UiPageHeaderComponent],
  template: `
    <ui-page-header
      title="Задачи"
      eyebrow="Рабочее пространство"
      subtitle="Все задачи, которые вам видны"
      titleId="tasks-title"
      countTestId="tasks-count"
      [count]="count()"
    >
      <span pageHeaderAside class="aside">вкладки</span>
      <button type="button" class="action">Новая задача</button>
    </ui-page-header>
  `,
})
class HostComponent {
  readonly count = signal<number | null>(5);
}

describe('ui-page-header', () => {
  function render() {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement.querySelector('ui-page-header') as HTMLElement };
  }

  it('is the screen header: a level-one title with its eyebrow, subtitle and counter', () => {
    const { host } = render();

    expect(host.classList).toContain('view-header');
    const title = host.querySelector('h1')!;
    expect(title.textContent?.trim()).toBe('Задачи');
    expect(title.id).toBe('tasks-title');
    expect(host.querySelector('.view-header__eyebrow')?.textContent).toContain('Рабочее пространство');
    expect(host.querySelector('.view-header__subtitle')?.textContent).toContain('Все задачи');
    expect(host.querySelector('[data-testid="tasks-count"]')?.textContent?.trim()).toBe('5');
  });

  it('puts the aside beside the title and the rest among the actions', () => {
    const { host } = render();

    expect(host.querySelector('.view-header__title-row .aside')).not.toBeNull();
    expect(host.querySelector('.view-header__actions .action')).not.toBeNull();
  });

  it('shows no counter without a count', () => {
    const { fixture, host } = render();
    fixture.componentInstance.count.set(null);
    fixture.detectChanges();

    expect(host.querySelector('.count-badge')).toBeNull();
  });
});
