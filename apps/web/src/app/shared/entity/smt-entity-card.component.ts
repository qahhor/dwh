import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import type { FormFieldMeta, FormMeta, FormValues } from '@core/models/form-meta.models';
import { fieldLabel } from '@core/services/form-meta.service';
import { I18nService } from '@core/services/i18n.service';
import { RefLookups } from '../lookups/ref-lookup';
import { UiMarkdownViewComponent } from '../ui/ui-markdown-view.component';
import { fieldText } from './entity-values';

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

  readonly meta = input.required<FormMeta>();

  /** The record's values by field key (`recordValues`). */
  readonly value = input<FormValues>({});

  /** The sections to show, by key; every section when empty. */
  readonly sections = input<readonly string[]>([]);

  readonly lines = computed<CardLine[]>(() => {
    this.i18n.currentLang();
    const translate = (key: string) => this.i18n.translate(key);
    const values = this.value();
    return this.shownFields().flatMap((field) => {
      const value = values[field.key];
      if (value === null || value === undefined || value === '') return [];
      return [
        {
          key: field.key,
          label: fieldLabel(field, translate),
          text: fieldText(field, value, translate, this.refLookups),
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
}
