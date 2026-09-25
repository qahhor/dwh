/* Our code, after the idea of the kit's `smt-tag`, `smt-tag-close-x` and
 * `smt-tag-group` (smartup-ui-kit@6472beb, components/tags). See ADR-0015
 * rule 2 and NOTICE.
 *
 * Why not the kit's copy: its tag drew avatars, dots and a close "x" without
 * a name for the button, and its group had no selection semantics; colours
 * were a palette per theme.
 *
 * - `smt-tag` is a label with an optional tone and, when removable, a remove
 *   button named "Remove «label»".
 * - `smt-tag-group` is a set of toggle tags — several may be chosen — for
 *   short choices such as a user's roles: each tag is a button with
 *   aria-pressed in a named group, its value the chosen keys in option order.
 *
 * Signal Forms: <smt-control [smtLabel]="…"><smt-tag-group [formField]="user.roleIds" [options]="roles" /></smt-control>
 * Plain:        <smt-tag-group [(value)]="roleIds" [options]="roles" smtAriaLabel="…" /> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../i18n';

export type SMTTagTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info';

@Component({
  selector: 'smt-tag',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './tag.scss',
  host: {
    class: 'smt-tag',
    '[class]': "'smt-tag smt-tag--' + tone()",
  },
  template: `
    <span class="smt-tag__label">{{ label() }}</span>
    @if (removable()) {
      <button
        type="button"
        class="smt-tag__remove"
        [disabled]="disabled()"
        [attr.aria-label]="i18n.messages().tag.remove(label())"
        (click)="remove.emit()">
        <span class="material-symbols-outlined" aria-hidden="true">close</span>
      </button>
    }
  `,
})
export class SMTTagComponent {
  readonly i18n = inject(SMTI18nService);

  readonly label = input.required<string>();

  readonly tone = input<SMTTagTone>('neutral', { alias: 'smtTone' });

  readonly removable = input(false, { alias: 'smtRemovable', transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly remove = output<void>({ alias: 'smtRemove' });
}

export interface SMTTagOption<K> {
  readonly value: K;
  readonly label: string;
  readonly disabled?: boolean;
  /** Material Symbols ligature after the label, such as a lock. */
  readonly icon?: string;
  /** Why the tag is as it is ("protected"): its title, and read after the label. */
  readonly note?: string;
}

let nextTagGroupId = 0;

@Component({
  selector: 'smt-tag-group',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './tag.scss',
  host: { class: 'smt-tag-group' },
  template: `
    <div
      class="smt-tag-group__list"
      role="group"
      [id]="customId() || groupId"
      [attr.aria-label]="ariaLabel() || null"
      [attr.aria-disabled]="isDisabled() ? 'true' : null"
      (focusout)="onFocusOut($event)">
      @for (option of options(); track $index) {
        <button
          type="button"
          class="smt-tag smt-tag--selectable"
          [class.smt-tag--chosen]="isChosen(option.value)"
          [attr.aria-pressed]="isChosen(option.value) ? 'true' : 'false'"
          [disabled]="isDisabled() || !!option.disabled"
          [attr.title]="option.note || null"
          (click)="toggle(option.value)">
          @if (isChosen(option.value)) {
            <span class="material-symbols-outlined smt-tag__check" aria-hidden="true">check</span>
          }
          <span class="smt-tag__label">{{ option.label }}</span>
          @if (option.icon) {
            <span class="material-symbols-outlined smt-tag__check" aria-hidden="true">{{ option.icon }}</span>
          }
          @if (option.note) {
            <span class="sr-only">{{ option.note }}</span>
          }
        </button>
      }
    </div>
  `,
})
export class SMTTagGroupComponent<K> implements FormValueControl<readonly K[]> {
  /** The group's id, for an outside label; not `id`, which would also land on the host element. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly options = input<readonly SMTTagOption<K>[]>([]);

  /** Names the group when no smt-control label does. */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly readonly = input(false, { transform: booleanAttribute });

  /** Bound by Signal Forms; the field's touched state. */
  readonly touched = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the group. */
  readonly touch = output<void>();

  readonly value = model<readonly K[]>([]);

  protected readonly wasTouched = linkedSignal(() => this.touched());

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.readonly() || this.formsDisabled());

  private readonly chosen = computed(() => new Set(this.value() ?? []));

  readonly groupId = `smt-tag-group-${nextTagGroupId++}`;

  isChosen(key: K): boolean {
    return this.chosen().has(key);
  }

  /** Adds or removes a key, keeping the options' order in the value. */
  toggle(key: K): void {
    if (this.isDisabled()) return;
    const next = new Set(this.chosen());
    if (next.has(key)) next.delete(key);
    else next.add(key);
    this.value.set(this.options().map(option => option.value).filter(value => next.has(value)));
  }

  onFocusOut(event: FocusEvent): void {
    const host = event.currentTarget as HTMLElement;
    if (host.contains(event.relatedTarget as Node | null)) return;
    this.wasTouched.set(true);
    this.touch.emit();
  }

  /** Called by SMTTagGroupValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
