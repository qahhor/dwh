/* Our code, after the idea of the kit's `smt-data-select` (smartup-ui-kit@6472beb,
 * components/forms/data-select). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it replaced Biruni's b-input over the kit's
 * QueryService (`p.column`, `p.sort`, `p.filter`), with its own popup,
 * pinning and add/view footer; our API pages by keyset and we already have
 * an accessible smt-select.
 *
 * This one is smt-select that feeds itself: give it a lookup source and it
 * loads the first page when opened, searches after a pause, loads more,
 * retries, keeps the chosen record named whatever the search shows, and asks
 * the source for the name of a record chosen before any page arrived (an edit
 * form). The screen writes neither the search plumbing nor its state.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-data-select [formField]="form.managerId" [source]="users" /></smt-control>
 * ngModel:      <smt-data-select [(ngModel)]="managerId" name="manager" [source]="users" /> */
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
import { SMTSelectComponent } from '../select/select.component';
import { LookupState, type SMTLookupKey, type SMTLookupSource } from './lookup-source';

@Component({
  selector: 'smt-data-select',
  standalone: true,
  imports: [SMTSelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'smt-data-select' },
  template: `
    <smt-select
      [options]="options()"
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
      [emptyLabel]="emptyLabel()"
      [allowClear]="allowClear()"
      [ariaLabel]="ariaLabel()"
      [smtTriggerId]="triggerId()"
      [smtColumnHeaders]="columnHeaders()"
      (valueChange)="choose($event)"
      (searchChange)="state.search($event)"
      (loadMore)="state.loadMore()"
      (retry)="state.retry()"
      (touch)="touch.emit()" />
  `,
  styles: [':host { display: block; min-width: 0; }'],
})
export class SMTDataSelectComponent<Row, K extends SMTLookupKey = number> implements FormValueControl<K | null> {
  readonly source = input.required<SMTLookupSource<Row, K>>();

  /** Rows not offered, such as the record being edited as its own manager. */
  readonly exclude = input<(row: Row) => boolean>(() => false);

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  readonly required = input(false, { transform: booleanAttribute });

  readonly placeholder = input('');

  readonly searchPlaceholder = input('');

  /** The "nothing chosen" row; empty — the select's own wording. */
  readonly emptyLabel = input('');

  readonly allowClear = input(true, { transform: booleanAttribute });

  readonly ariaLabel = input('');

  readonly triggerId = input<string | undefined>(undefined, { alias: 'smtTriggerId' });

  readonly columnHeaders = input<readonly string[]>([], { alias: 'smtColumnHeaders' });

  readonly touch = output<void>();

  /** The chosen record, when the person picks one; null when they clear the field. */
  readonly rowChange = output<Row | null>();

  readonly value = model<K | null>(null);

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  /** The chosen record first, then the rows of the current search. */
  readonly options = computed(() => {
    const value = this.value();
    const exclude = this.exclude();
    const chosen = value === null ? [] : this.state.chosen([value]);
    return [...chosen, ...this.state.listed(row => exclude(row) || this.source().key(row) === value)];
  });

  readonly state = new LookupState<Row, K>(() => this.source());

  constructor() {
    effect(() => {
      const value = this.value();
      if (value !== null) untracked(() => this.state.resolve([value]));
    });
    inject(DestroyRef).onDestroy(() => this.state.cancel());
  }

  choose(value: K | null): void {
    this.value.set(value);
    this.rowChange.emit(value === null ? null : this.state.rowOf(value) ?? null);
  }

  /** Called by SMTDataSelectValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
