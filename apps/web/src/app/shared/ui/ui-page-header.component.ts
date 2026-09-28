import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The header of a screen (plan 10/10, item 2.6): the title, an optional eyebrow, subtitle and counter, what
 * sits beside the title (tabs, a filter) and the screen's actions. One look for every screen instead of a
 * `.view-header` copied into each.
 *
 * <ui-page-header [title]="'notes.title' | t" [count]="total()">
 *   <smt-tab-bar pageHeaderAside ... />
 *   <button smt-button ...>New note</button>
 * </ui-page-header>
 *
 * Content marked `pageHeaderAside` follows the title; everything else is an action on the right.
 */
@Component({
  selector: 'ui-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'view-header' },
  template: `
    <div class="view-header__main">
      @if (eyebrow()) {
        <p class="view-header__eyebrow">{{ eyebrow() }}</p>
      }
      <div class="view-header__title-row">
        <h1 class="view-title" [id]="titleId() || null">{{ title() }}</h1>
        @if (count() !== null && count() !== undefined) {
          <span class="count-badge" [attr.title]="countLabel() || null" [attr.data-testid]="countTestId() || null">
            {{ count() }}
          </span>
        }
        <ng-content select="[pageHeaderAside]" />
      </div>
      @if (subtitle()) {
        <p class="view-header__subtitle">{{ subtitle() }}</p>
      }
    </div>
    <div class="view-header__actions">
      <ng-content />
    </div>
  `,
  styleUrl: './ui-page-header.component.css',
})
export class UiPageHeaderComponent {
  readonly title = input.required<string>();
  readonly eyebrow = input('');
  readonly subtitle = input('');
  /** A number shown beside the title (records on screen, a total); nothing when null. */
  readonly count = input<number | string | null | undefined>(null);
  readonly countLabel = input('');
  readonly countTestId = input('');
  /** The heading's id, for a region named by it (aria-labelledby). */
  readonly titleId = input('');
}
