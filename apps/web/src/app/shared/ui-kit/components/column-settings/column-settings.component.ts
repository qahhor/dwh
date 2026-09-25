/* Our code, after the idea of the kit's data-table settings modal
 * (smartup-ui-kit@6472beb, components/data-table/components/data-table-settings-modal).
 * See ADR-0015 rule 2.
 *
 * The kit reordered columns by dragging chips, which a keyboard or screen
 * reader cannot do, and resized them inside the dialog. Here a button opens a
 * small non-modal panel next to it: a checkbox per column to show or hide it,
 * "up" and "down" buttons to move it (every move is announced with the new
 * place, and focus stays on the moved column), and "Reset". Widths are set by
 * dragging the header edge in the table itself; Reset clears them too.
 * Locked columns cannot be hidden, and the last visible column cannot be
 * hidden either, so the table never ends up empty.
 *
 *   <smt-column-settings [smtColumns]="columns" [(smtState)]="state" /> */
import { A11yModule } from '@angular/cdk/a11y';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { SMTI18nService } from '../../i18n';
import {
  EMPTY_COLUMN_STATE,
  moveColumn,
  normalizeColumnState,
  setColumnVisible,
  TableColumnState,
} from '../table/column-state';

export interface SMTColumnOption {
  readonly key: string;
  readonly label: string;
  /** Always shown: the column that names the row, for instance. */
  readonly locked?: boolean;
}

interface Row extends SMTColumnOption {
  readonly visible: boolean;
}

let nextColumnSettingsId = 0;

@Component({
  selector: 'smt-column-settings',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, A11yModule],
  templateUrl: './column-settings.component.html',
  styleUrl: './column-settings.scss',
  host: { class: 'smt-columns-host' },
})
export class SMTColumnSettingsComponent {
  readonly i18n = inject(SMTI18nService);
  private readonly injector = inject(Injector);

  /** Every column the table can show, in its default order. */
  readonly columns = input.required<readonly SMTColumnOption[]>({ alias: 'smtColumns' });

  readonly state = model<TableColumnState>(EMPTY_COLUMN_STATE, { alias: 'smtState' });

  readonly panelId = `smt-column-settings-${nextColumnSettingsId++}`;
  readonly open = signal(false);
  readonly announcement = signal('');

  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  readonly positions: ConnectedPosition[] = [
    { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 4 },
    { originX: 'end', originY: 'top', overlayX: 'end', overlayY: 'bottom', offsetY: -4 },
  ];

  readonly rows = computed<Row[]>(() => {
    const columns = this.columns();
    const byKey = new Map(columns.map(column => [column.key, column]));
    const locked = columns.filter(column => column.locked).map(column => column.key);
    const normalized = normalizeColumnState(this.state(), columns.map(column => column.key), locked);
    return normalized.order.map(key => ({ ...byKey.get(key)!, visible: !normalized.hidden.includes(key) }));
  });

  readonly visibleCount = computed(() => this.rows().filter(row => row.visible).length);

  toggle(): void {
    if (this.open()) {
      this.close(true);
    } else {
      this.announcement.set('');
      this.open.set(true);
    }
  }

  close(returnFocus: boolean): void {
    if (!this.open()) return;
    this.open.set(false);
    if (returnFocus) this.trigger().nativeElement.focus();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close(true);
    }
  }

  setVisible(row: Row, visible: boolean): void {
    this.state.set(setColumnVisible(this.current(), row.key, visible));
  }

  move(row: Row, delta: -1 | 1): void {
    const next = moveColumn(this.current(), row.key, delta);
    this.state.set(next);
    const position = next.order.indexOf(row.key) + 1;
    this.announcement.set(this.i18n.messages().columns.moved(row.label, position, next.order.length));
    this.keepFocus(row.key, delta);
  }

  reset(): void {
    this.state.set(EMPTY_COLUMN_STATE);
    this.announcement.set(this.i18n.messages().columns.resetDone);
  }

  /** The full current state, so a move works on every column, not only those the person already touched. */
  private current(): TableColumnState {
    const columns = this.columns();
    return normalizeColumnState(
      this.state(),
      columns.map(column => column.key),
      columns.filter(column => column.locked).map(column => column.key),
    );
  }

  /** The moved column keeps focus; at the edge its button is disabled, so focus goes to the other one. */
  private keepFocus(key: string, delta: -1 | 1): void {
    afterNextRender(() => {
      const panel = document.getElementById(this.panelId);
      const same = panel?.querySelector<HTMLButtonElement>(`[data-column="${key}"][data-move="${delta}"]`);
      const other = panel?.querySelector<HTMLButtonElement>(`[data-column="${key}"][data-move="${-delta}"]`);
      (same && !same.disabled ? same : other)?.focus();
    }, { injector: this.injector });
  }
}
