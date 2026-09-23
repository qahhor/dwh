/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/cell-content/cell-content.component.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { ColumnContentType } from '../table.types';
import { SMTCheckboxComponent } from '../../forms/checkbox/checkbox.component';
import { CbPipe } from '../../../utils/cb.pipe';
import { DatePipe, NgComponentOutlet, NgTemplateOutlet } from '@angular/common';
import { SMTI18nService } from '../../../i18n';

@Component({
  selector: 'smt-cell-content',
  standalone: true,
  imports: [SMTCheckboxComponent, CbPipe, DatePipe, NgTemplateOutlet, NgComponentOutlet],
  templateUrl: './cell-content.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'flex min-w-0 items-center',
    // Dedicated checkbox column: no gap / no stretch beside an empty content sibling.
    '[class.gap-3]': '!selectionOnly()',
    '[class.w-full]': '!selectionOnly()',
    '[class.h-full]': 'selectionOnly()',
    '[class.ps-2]': 'selectionOnly()',
    '[class.pe-1]': 'selectionOnly()',
    '[class.justify-center]': 'selectionOnly() || (!selectionOnly() && align() === "center")',
    '[class.justify-end]': '!selectionOnly() && align() === "right"',
    '[class.text-end]': '!selectionOnly() && align() === "right"',
    '[class.text-center]': '!selectionOnly() && align() === "center"',
  },
})
export class SMTCellContentComponent<T> {
  readonly i18n = inject(SMTI18nService);

  data = input.required<ColumnContentType<T>>({ alias: 'smtData' });

  row = input.required<T>({ alias: 'smtRow' });

  hasSelection = input(false, { alias: 'smtHasSelection' });

  isSelected = input(false, { alias: 'smtIsSelected' });

  /** Biruni column `align` — mirrors header alignment. */
  align = input<'left' | 'center' | 'right'>('left', { alias: 'smtAlign' });

  isSelectedChange = output<boolean>({ alias: 'smtIsSelectedChange' });

  /** Dedicated Biruni checkbox column: selection UI only, no cell value. */
  protected selectionOnly = computed(() => {
    if (!this.hasSelection()) return false;
    const data = this.data();
    if (data.type !== 'primitive') return false;
    const value = data.value;
    if (typeof value === 'function') {
      try {
        const resolved = value(this.row());
        return resolved == null || resolved === '';
      } catch {
        return false;
      }
    }
    return value == null || value === '';
  });
}
