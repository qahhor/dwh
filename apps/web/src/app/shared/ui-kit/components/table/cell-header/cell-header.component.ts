/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/table/cell-header/cell-header.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  LOCALE_ID,
  inject,
  input,
  model,
  OnDestroy,
  OnInit,
  output,
  Renderer2,
  signal,
  viewChild,
} from '@angular/core';
import { ColumnHeaderType, OrderBy } from '../table.types';
import { SMTCheckboxComponent } from '../../forms/checkbox/checkbox.component';
import { SMTSortIconComponent } from '../sort-icon/sort-icon.component';
import { DatePipe, formatDate, NgComponentOutlet, NgTemplateOutlet } from '@angular/common';
import { SMTTooltipDirective } from '../../../directives/tooltip/tooltip.directive';
import { SMTI18nService } from '../../../i18n';

@Component({
  selector: 'smt-cell-header',
  standalone: true,
  imports: [
    SMTCheckboxComponent,
    SMTSortIconComponent,
    DatePipe,
    NgTemplateOutlet,
    NgComponentOutlet,
    SMTTooltipDirective,
  ],
  templateUrl: './cell-header.component.html',
  host: {
    class:
      'flex h-full items-center select-none min-w-0 overflow-hidden [&.has-sorting]:cursor-pointer [&.has-sorting]:hover:bg-gray-100 focus-visible:-outline-offset-2!',
    '[class.has-sorting]': `hasSorting()`,
    // A sortable header is a control: reachable by Tab and operable by Enter
    // and Space, not only by a pointer. `aria-sort` sits on the columnheader.
    '[attr.role]': `hasSorting() ? 'button' : null`,
    '[attr.tabindex]': `hasSorting() ? 0 : null`,
    // Regular header cells keep min-h-9; checkbox-only column must not — it stacks with the cell wrapper.
    '[class.min-h-9]': '!selectionOnly()',
    // Dedicated checkbox column: same padding as body checkbox cells (Biruni ~9px).
    '[class.gap-3]': '!selectionOnly()',
    '[class.px-4]': '!selectionOnly()',
    '[class.py-2]': '!selectionOnly()',
    '[class.ps-2]': 'selectionOnly()',
    '[class.pe-1]': 'selectionOnly()',
    '[class.justify-center]': 'selectionOnly() || (!selectionOnly() && align() === "center")',
    '[class.justify-end]': '!selectionOnly() && align() === "right"',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SMTCellHeaderComponent implements OnInit, OnDestroy {
  readonly i18n = inject(SMTI18nService);

  private renderer = inject(Renderer2);

  private hostElement = inject(ElementRef);

  private locale = inject(LOCALE_ID);

  data = input.required<ColumnHeaderType>({ alias: 'smtData' });

  hasSorting = input(false, { alias: 'smtHasSorting' });

  hasSelection = input(false, { alias: 'smtHasSelection' });

  /** Biruni column `align` — header mirrors body. */
  align = input<'left' | 'center' | 'right'>('left', { alias: 'smtAlign' });

  selectedType = input<'all-selected' | 'not-selected' | 'partial-selected'>('not-selected', {
    alias: 'smtSelectedType',
  });

  checkboxClick = output<boolean>({ alias: 'smtCheckboxClick' });

  sort = model<OrderBy | undefined>(undefined, { alias: 'smtSort' });

  protected headerTextRef = viewChild<ElementRef<HTMLElement>>('headerText');

  protected isHeaderOverflowing = signal(false);

  protected tooltipText = computed(() => this.getHeaderTooltipText(this.data()));

  /** Dedicated Biruni checkbox column: selection UI only, no header label. */
  protected selectionOnly = computed(() => {
    if (!this.hasSelection()) return false;
    const data = this.data();
    if (data.type !== 'primitive') return false;
    return data.value == null || data.value === '';
  });

  private clickListenerDispose?: () => void;

  private resizeObserver?: ResizeObserver;

  constructor() {
    afterNextRender(() => {
      const textElement = this.headerTextRef()?.nativeElement;
      if (!textElement) return;

      this.updateTooltipOverflow();
      if (typeof ResizeObserver === 'undefined') return;
      this.resizeObserver = new ResizeObserver(() => this.updateTooltipOverflow());
      this.resizeObserver.observe(textElement);
    });
  }

  ngOnInit() {
    if (this.hasSorting()) {
      const host = this.hostElement.nativeElement;
      const disposeClick = this.renderer.listen(host, 'click', () => this.changeSorting());
      const disposeKey = this.renderer.listen(host, 'keydown', (event: KeyboardEvent) => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        this.changeSorting();
      });
      this.clickListenerDispose = () => {
        disposeClick();
        disposeKey();
      };
    }
  }

  ngOnDestroy() {
    this.clickListenerDispose?.();
    this.resizeObserver?.disconnect();
  }

  protected updateTooltipOverflow(): void {
    const textElement = this.headerTextRef()?.nativeElement;
    if (!textElement) {
      this.isHeaderOverflowing.set(false);
      return;
    }

    const selfOverflow = textElement.scrollWidth - textElement.clientWidth > 0.5;
    const child = textElement.firstElementChild as HTMLElement | null;
    const childOverflow = !!child && child.scrollWidth - child.clientWidth > 0.5;
    this.isHeaderOverflowing.set(selfOverflow || childOverflow);
  }

  private changeSorting() {
    switch (this.sort()) {
      case 'ASC':
        this.sort.set(OrderBy.Desc);
        break;
      case 'DESC':
        this.sort.set(undefined);
        break;
      default:
        this.sort.set(OrderBy.Asc);
        break;
    }
  }

  private getHeaderTooltipText(data: ColumnHeaderType): string {
    switch (data.type) {
      case 'primitive':
        return data.value == null ? '' : String(data.value);
      case 'html':
        return this.stripHtml(data.value);
      case 'date':
        return this.formatHeaderDate(data.value, data.format || 'd MMM y');
      case 'date-time':
        return this.formatHeaderDate(data.value, data.format || 'd MMM y, H:mm a');
      default:
        return '';
    }
  }

  private formatHeaderDate(value: Date | string, format: string): string {
    try {
      return formatDate(value, format, this.locale);
    } catch {
      return String(value);
    }
  }

  private stripHtml(value: string): string {
    return value
      .replace(/<[^>]*>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }
}
