import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { UPL_ENCODINGS, UPL_FILE_KINDS, UPL_MATCH_BY, UplFileKind, UplFormatDraftRequest } from '../upl-api';
import { UPL_ENCODING_KEY, UPL_FILE_KIND_KEY, UPL_MATCH_BY_KEY } from '../upl-labels';
import { isFilled } from './upl-format-model';

/** Шаг «Файл» анкеты: вид файла, кодировка и разделитель CSV, сопоставление колонок. Правит модель на месте. */
@Component({
  selector: 'app-upl-format-file-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TranslatePipe],
  template: `
    <h2 class="upl-block-title">{{ 'upl.format.file' | t }}</h2>
    <div class="upl-row">
      <div class="form-group">
        <label class="form-label" for="upl-file-kind">{{ 'upl.format.field.file_kind' | t }}</label>
        <select
          id="upl-file-kind"
          class="form-select"
          data-testid="upl-file-kind"
          [disabled]="!editable()"
          [ngModel]="model().fileKind"
          [ngModelOptions]="{ standalone: true }"
          (ngModelChange)="onFileKindChange($event)"
        >
          @for (kind of fileKinds; track kind) {
            <option [value]="kind">{{ fileKindKey[kind] | t }}</option>
          }
        </select>
      </div>
      @if (model().fileKind === 'csv') {
        <div class="form-group">
          <label class="form-label" for="upl-encoding">{{ 'upl.format.field.encoding' | t }}</label>
          <select
            id="upl-encoding"
            class="form-select"
            data-testid="upl-encoding"
            [disabled]="!editable()"
            [(ngModel)]="model().encoding"
            [ngModelOptions]="{ standalone: true }"
          >
            @for (encoding of encodings; track encoding) {
              <option [value]="encoding">{{ encodingKey[encoding] | t }}</option>
            }
          </select>
        </div>
        <div class="form-group">
          <label class="form-label" for="upl-delimiter">{{ 'upl.format.field.delimiter' | t }}</label>
          <input
            id="upl-delimiter"
            class="form-input upl-input-tiny"
            type="text"
            maxlength="1"
            [disabled]="!editable()"
            [(ngModel)]="model().delimiter"
            [ngModelOptions]="{ standalone: true }"
          />
        </div>
      }
      <div class="form-group">
        <label class="form-label" for="upl-match-by">{{ 'upl.format.field.match_by' | t }}</label>
        <select
          id="upl-match-by"
          class="form-select"
          data-testid="upl-match-by"
          [disabled]="!editable()"
          [(ngModel)]="model().matchColumnsBy"
          [ngModelOptions]="{ standalone: true }"
        >
          @for (match of matchBy; track match) {
            <option [value]="match">{{ matchByKey[match] | t }}</option>
          }
        </select>
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 0.75rem; }
    .upl-block-title { margin: 0; font-size: 1rem; color: var(--text-main); }
    .upl-row { display: flex; flex-wrap: wrap; gap: 1rem; align-items: flex-start; }
    .upl-input-tiny { max-width: 4rem; }
  `]
})
export class FormatFileStepComponent {
  readonly model = input.required<UplFormatDraftRequest>();
  readonly editable = input(false);

  readonly fileKinds = UPL_FILE_KINDS;
  readonly encodings = UPL_ENCODINGS;
  readonly matchBy = UPL_MATCH_BY;
  readonly fileKindKey = UPL_FILE_KIND_KEY;
  readonly encodingKey = UPL_ENCODING_KEY;
  readonly matchByKey = UPL_MATCH_BY_KEY;

  onFileKindChange(kind: UplFileKind): void {
    const model = this.model();
    model.fileKind = kind;
    if (kind === 'csv') {
      if (!isFilled(model.encoding)) model.encoding = 'utf-8';
      if (!isFilled(model.delimiter)) model.delimiter = ';';
    }
  }
}
