/* Our code, after the idea of the kit's `smt-multi-data-select`
 * (smartup-ui-kit@6472beb, components/forms/multi-data-select). See ADR-0015
 * rule 2 and NOTICE, and smt-data-select for why the kit's copy was not taken.
 *
 * smt-multi-select that feeds itself from a lookup source: chips of chosen
 * records keep their names whatever the search shows, and records chosen
 * before any page arrived are named through the source's `resolve`.
 *
 * Signal Forms: <smt-multi-data-select [formField]="task.observers" [source]="users" />
 * ngModel:      <smt-multi-data-select [(ngModel)]="observerIds" name="observers" [source]="users" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTMultiSelectComponent } from '../multi-select/multi-select.component';
import { LookupState, type SMTLookupKey, type SMTLookupSource } from './lookup-source';

@Component({
  selector: 'smt-multi-data-select',
  standalone: true,
  imports: [SMTMultiSelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'smt-multi-data-select' },
  template: `
    <smt-multi-select
      [options]="options()"
      [knownOptions]="chosen()"
      [value]="value()"
      [remoteSearch]="true"
      [loading]="state.loading()"
      [loadError]="state.error()"
      [hasMore]="state.hasMore()"
      [disabled]="isDisabled()"
      [readonly]="readonly()"
      [required]="required()"
      [placeholder]="placeholder()"
      [searchPlaceholder]="searchPlaceholder()"
      [ariaLabel]="ariaLabel()"
      (valueChange)="choose($event)"
      (searchChange)="state.search($event)"
      (loadMore)="state.loadMore()"
      (retry)="state.retry()"
      (touch)="touch.emit()" />
  `,
  styles: [':host { display: block; min-width: 0; }'],
})
export class SMTMultiDataSelectComponent<Row, K extends SMTLookupKey = number> implements FormValueControl<readonly K[]> {
  readonly source = input.required<SMTLookupSource<Row, K>>();

  /** Rows not offered in the list; chosen ones stay as chips. */
  readonly exclude = input<(row: Row) => boolean>(() => false);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly placeholder = input('');

  readonly searchPlaceholder = input('');

  readonly ariaLabel = input('');

  readonly touch = output<void>();

  /** The chosen records the person's change leaves, in the order of the value; unnamed keys are left out. */
  readonly rowsChange = output<Row[]>();

  readonly value = model<readonly K[]>([]);

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  /** Chips of the chosen records, named when known. */
  readonly chosen = computed(() => this.state.chosen(this.value() ?? []));

  readonly options = computed(() => {
    const exclude = this.exclude();
    return this.state.listed(row => exclude(row));
  });

  readonly state = new LookupState<Row, K>(() => this.source());

  constructor() {
    effect(() => {
      const keys = this.value() ?? [];
      if (keys.length > 0) untracked(() => this.state.resolve(keys));
    });
    inject(DestroyRef).onDestroy(() => this.state.cancel());
  }

  choose(keys: readonly K[]): void {
    this.value.set(keys);
    this.rowsChange.emit(keys.map(key => this.state.rowOf(key)).filter((row): row is Row => row !== undefined));
  }

  /** Called by SMTMultiDataSelectValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
