/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/sort-icon/sort-icon.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { SMTIconComponent } from '../../icon/icon';
import { injectRegisterSMTIcons } from '../../../providers/svg-icon.provider';
import { sortIcon } from '../../../svg-icons';

@Component({
  selector: 'smt-sort-icon',
  standalone: true,
  imports: [SMTIconComponent],
  template: `<smt-icon [key]="'sort'" [width]="12" [height]="12" />`,
  styles: `
    :host {
      display: flex;
    }

    :host ::ng-deep [data-stroke='asc'],
    :host ::ng-deep [data-stroke='desc'] {
      stroke: var(--color-gray-400);
    }

    :host[data-sort='ASC'] ::ng-deep [data-stroke='desc'] {
      stroke: var(--color-brand-600);
    }

    :host[data-sort='DESC'] ::ng-deep [data-stroke='asc'] {
      stroke: var(--color-brand-600);
    }
  `,
  host: {
    '[attr.data-sort]': 'sort()',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SMTSortIconComponent {
  sort = input<'ASC' | 'DESC' | undefined>(undefined, { alias: 'smtSort' });

  constructor() {
    injectRegisterSMTIcons([sortIcon]);
  }
}
