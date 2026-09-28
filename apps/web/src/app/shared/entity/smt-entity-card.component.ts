import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, signal } from '@angular/core';
import type { FormFieldMeta, FormMeta, FormValues } from '../../core/models/form-meta.models';
import { fieldLabel, optionLabel } from '../../core/services/form-meta.service';
import { I18nService } from '../../core/services/i18n.service';
import { RefLookups } from '../lookups/ref-lookup';
import { UiMarkdownViewComponent } from '../ui/ui-markdown-view.component';

interface CardLine {
  key: string;
  label: string;
  text: string;
  markdown: boolean;
}

/**
 * A record read by its entity's layout (ADR-0019 2.5, roadmap item 55): each filled field as its label and its
 * value in words — an option by its label, yes/no, a referenced record by its name. Empty fields are left out,
 * so a card of a few sections stays short.
 */
@Component({
  selector: 'smt-entity-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UiMarkdownViewComponent],
  host: { class: 'smt-entity-card' },
  template: `
    @if (lines().length > 0) {
      <dl class="entity-card">
        @for (line of lines(); track line.key) {
          <div class="entity-card-line" [attr.data-field]="line.key">
            <dt>{{ line.label }}</dt>
            <dd>
              @if (line.markdown) {
                <ui-markdown-view [content]="line.text" />
              } @else {
                {{ line.text }}
              }
            </dd>
          </div>
        }
      </dl>
    }
  `,
  styles: [
    `
      .entity-card {
        display: grid;
        gap: 4px;
        margin: 0;
      }
      .entity-card-line {
        display: flex;
        gap: 6px;
        min-width: 0;
        font-size: 0.8125rem;
      }
      dt {
        color: var(--text-secondary, inherit);
        flex: none;
      }
      dt::after {
        content: ':';
      }
      dd {
        margin: 0;
        min-width: 0;
        overflow-wrap: anywhere;
      }
    `,
  ],
})
export class SMTEntityCardComponent {
  private readonly i18n = inject(I18nService);

  private readonly refLookups = inject(RefLookups);

  private readonly destroyRef = inject(DestroyRef);

  readonly meta = input.required<FormMeta>();

  /** The record's values by field key (`recordValues`). */
  readonly value = input<FormValues>({});

  /** The sections to show, by key; every section when empty. */
  readonly sections = input<readonly string[]>([]);

  /** Names of referenced records by field key and id, filled as they are read. */
  private readonly names = signal<Record<string, string>>({});

  readonly lines = computed<CardLine[]>(() => {
    this.i18n.currentLang();
    const translate = (key: string) => this.i18n.translate(key);
    const values = this.value();
    const names = this.names();
    return this.shownFields().flatMap((field) => {
      const value = values[field.key];
      if (value === null || value === undefined || value === '') return [];
      return [
        {
          key: field.key,
          label: fieldLabel(field, translate),
          text: this.textOf(field, value, names, translate),
          markdown: field.type === 'markdown',
        },
      ];
    });
  });

  private readonly shownFields = computed<FormFieldMeta[]>(() => {
    const meta = this.meta();
    const only = this.sections();
    const byKey = new Map(meta.fields.map((field) => [field.key, field]));
    return meta.layout
      .filter((section) => only.length === 0 || only.includes(section.key))
      .flatMap((section) =>
        section.fields.map((key) => byKey.get(key)).filter((field): field is FormFieldMeta => !!field),
      );
  });

  /** References already asked for, so a name is read once. */
  private readonly asked = new Set<string>();

  constructor() {
    effect(() => {
      const values = this.value();
      for (const field of this.shownFields()) {
        const id = values[field.key];
        const ref = field.ref;
        if (!ref || id === null || id === undefined || id === '') continue;
        const wanted = nameKey(field.key, id);
        if (this.asked.has(wanted)) continue;
        this.asked.add(wanted);
        const subscription = this.refLookups
          .source(ref)
          .resolve?.([id as string | number])
          .subscribe((rows) => {
            const row = rows[0] as Record<string, unknown> | undefined;
            if (row) this.names.update((names) => ({ ...names, [wanted]: String(row[ref.labelField] ?? id) }));
          });
        if (subscription) this.destroyRef.onDestroy(() => subscription.unsubscribe());
      }
    });
  }

  private textOf(
    field: FormFieldMeta,
    value: unknown,
    names: Record<string, string>,
    translate: (key: string) => string,
  ): string {
    switch (field.type) {
      case 'boolean':
        return translate(value === true || value === 'true' ? 'common.yes' : 'common.no');
      case 'select':
        return optionLabel(field, String(value), translate);
      case 'ref':
        return names[nameKey(field.key, value)] ?? String(value);
      default:
        return String(value);
    }
  }
}

function nameKey(field: string, id: unknown): string {
  return `${field}:${String(id)}`;
}
