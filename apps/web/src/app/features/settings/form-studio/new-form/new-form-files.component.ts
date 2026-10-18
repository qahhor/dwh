import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { MAX_FIELD_FILES, MAX_FILE_SIZE_MB, type SimpleFieldFiles } from './new-form.models';

/**
 * The rules of a file field of a live form (ADR-0043, format 7): one file or several up to a count, the content types
 * it takes and its largest size. In a new version a stored field keeps one file or several for good and never holds
 * fewer files than before; its types and size change freely. Files go through the files module only — its content
 * check, virus scan and quotas — and the record keeps their ids.
 */
@Component({
  selector: 'app-new-form-files',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTCheckboxComponent, SMTControlComponent, SMTInputComponent],
  template: `
    <fieldset class="field-files" [attr.data-testid]="'field-files-' + index()">
      <legend>{{ 'studio.files.title' | t }}</legend>
      <div
        smt-checkbox
        [checked]="rules().multiple"
        [disabled]="disabled() || stored() !== null"
        [smtHint]="(stored() !== null ? 'studio.files.multiple_locked' : 'studio.files.multiple_hint') | t"
        [attr.data-testid]="'field-files-multiple-' + index()"
        (checkedChange)="setMultiple($event)"
      >
        {{ 'studio.files.multiple' | t }}
      </div>
      @if (rules().multiple) {
        <smt-control
          [smtLabel]="'studio.files.max_files' | t"
          [required]="true"
          [smtHint]="'studio.files.max_files_hint' | t: { min: minFiles(), max: maxFiles }"
        >
          <smt-input
            type="number"
            [value]="rules().maxFiles ?? null"
            [smtMin]="minFiles()"
            [smtMax]="maxFiles"
            [disabled]="disabled()"
            [attr.data-testid]="'field-files-max-' + index()"
            (valueChange)="patch({ maxFiles: number($event) })"
          />
        </smt-control>
      }
      <smt-control [smtLabel]="'studio.files.content_types' | t" [smtHint]="'studio.files.content_types_hint' | t">
        <smt-input
          [value]="(rules().contentTypes ?? []).join(', ')"
          [maxLength]="2000"
          [disabled]="disabled()"
          [attr.data-testid]="'field-files-types-' + index()"
          (valueChange)="setTypes($event)"
        />
      </smt-control>
      <smt-control
        [smtLabel]="'studio.files.max_size' | t"
        [smtHint]="'studio.files.max_size_hint' | t: { max: maxSize }"
      >
        <smt-input
          type="number"
          [value]="rules().maxSizeMb ?? null"
          [smtMin]="1"
          [smtMax]="maxSize"
          [disabled]="disabled()"
          [attr.data-testid]="'field-files-size-' + index()"
          (valueChange)="patch({ maxSizeMb: number($event) })"
        />
      </smt-control>
    </fieldset>
  `,
  styles: [
    `
      .field-files {
        min-width: 0;
        margin: 0;
        padding: 12px;
        border: 1px solid var(--border-color);
        border-radius: 8px;
        display: grid;
        gap: 12px;
      }
      .field-files legend {
        color: var(--text-main);
        font-size: 13px;
        font-weight: 600;
      }
    `,
  ],
})
export class NewFormFilesComponent {
  readonly index = input.required<number>();
  readonly files = input<SimpleFieldFiles | undefined>(undefined);
  readonly disabled = input(false);
  /** The rules the active version stores, or null for a field added in this version. */
  readonly stored = input<SimpleFieldFiles | null>(null);
  readonly changed = output<SimpleFieldFiles>();

  readonly rules = computed<SimpleFieldFiles>(() => this.files() ?? { multiple: false });

  /** A stored field never holds fewer files than before. */
  readonly minFiles = computed(() => Math.max(2, this.stored()?.maxFiles ?? 2));

  readonly maxFiles = MAX_FIELD_FILES;
  readonly maxSize = MAX_FILE_SIZE_MB;

  setMultiple(multiple: boolean): void {
    if (this.stored() !== null) return;
    const next: SimpleFieldFiles = { ...this.rules(), multiple };
    if (multiple) next.maxFiles = next.maxFiles ?? 5;
    else delete next.maxFiles;
    this.changed.emit(next);
  }

  setTypes(value: unknown): void {
    const types = String(value ?? '')
      .split(',')
      .map((type) => type.trim())
      .filter(Boolean);
    const next: SimpleFieldFiles = { ...this.rules() };
    if (types.length) next.contentTypes = types;
    else delete next.contentTypes;
    this.changed.emit(next);
  }

  patch(change: Partial<SimpleFieldFiles>): void {
    const next: SimpleFieldFiles = { ...this.rules(), ...change };
    if (next.maxSizeMb === undefined) delete next.maxSizeMb;
    this.changed.emit(next);
  }

  number(value: unknown): number | undefined {
    return typeof value === 'number' ? value : undefined;
  }
}
