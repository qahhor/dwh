/* Adapter, not vendored code.
 *
 * The kit's own icon component renders an SVG sprite through
 * `@ngneat/svg-icon`. This application already has one icon set, the
 * Material Symbols font shipped in src/assets/fonts, and carrying a second
 * icon system for one component would mean two sprite pipelines and two
 * sets of names to keep in step. This presents the same `smt-icon` surface
 * the vendored components bind to, and renders a Material Symbol. */
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Kit icon key -> Material Symbol name. */
const SYMBOL: Readonly<Record<string, string>> = {
  'chevron-up': 'expand_less',
  'chevron-down': 'expand_more',
  'left-arrow': 'chevron_left',
  'right-arrow': 'chevron_right',
  'menu-01': 'menu',
  'empty-state': 'inbox',
  search: 'search',
  check: 'check',
  loader: 'progress_activity',
};

@Component({
  selector: 'smt-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'img', 'aria-hidden': 'true', '[style.font-size]': 'sizePx()' },
  template: `<span class="material-symbols-outlined" [style.font-size]="sizePx()">{{ symbol() }}</span>`,
})
export class SMTIconComponent {
  readonly key = input.required<string>();
  readonly fontSize = input<number | string>('1rem');
  readonly size = input<string>();
  readonly color = input<string>();
  /* Accepted so the vendored templates bind unchanged. A font glyph is sized
     by font-size, so explicit width and height are advisory here. */
  readonly width = input<number | string>();
  readonly height = input<number | string>();

  /** An unmapped key falls through unchanged: Material Symbol names are valid keys too. */
  protected readonly symbol = computed(() => SYMBOL[this.key()] ?? this.key());

  protected readonly sizePx = computed(() => {
    const value = this.fontSize();
    return typeof value === 'number' ? `${value}px` : value;
  });
}
