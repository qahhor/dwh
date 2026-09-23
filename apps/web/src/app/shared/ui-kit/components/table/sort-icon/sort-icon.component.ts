/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/sort-icon/sort-icon.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { SMTIconComponent } from '../../icon/icon';

/* The kit colours two strokes of an SVG sprite to show direction. Icons here
   are a font glyph with no strokes to colour, so that styling never applied
   and every column looked unsorted. The direction is now its own glyph, and
   the active state its own ink. Assistive technology reads `aria-sort` from
   the columnheader, so the glyph is decorative. */
@Component({
  selector: 'smt-sort-icon',
  standalone: true,
  imports: [SMTIconComponent],
  template: `<smt-icon [key]="symbol()" [fontSize]="16" [class]="sort() ? 'text-brand-600' : 'text-gray-400'" />`,
  styles: `
    :host {
      display: flex;
    }
  `,
  host: {
    '[attr.data-sort]': 'sort()',
    'aria-hidden': 'true',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SMTSortIconComponent {
  sort = input<'ASC' | 'DESC' | undefined>(undefined, { alias: 'smtSort' });

  protected readonly symbol = computed(() => {
    const sort = this.sort();
    if (sort === 'ASC') return 'arrow_upward';
    if (sort === 'DESC') return 'arrow_downward';
    return 'swap_vert';
  });
}
