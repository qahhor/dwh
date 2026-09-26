import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UPL_ENCODINGS, UPL_FILE_KINDS, UPL_MATCH_BY, UplEncoding, UplFileKind, UplFormatDraftRequest, UplMatchBy } from '../upl-api';
import { UPL_ENCODING_KEY, UPL_FILE_KIND_KEY, UPL_MATCH_BY_KEY } from '../upl-labels';
import { isFilled } from './upl-format-model';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';

/** Шаг «Файл» анкеты: вид файла, кодировка и разделитель CSV, сопоставление колонок. Правит модель на месте. */
@Component({
  selector: 'app-upl-format-file-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTInputValueAccessor, SMTSelectComponent, SMTSelectValueAccessor, FormsModule, TranslatePipe],
  template: `
    <h2 class="upl-block-title">{{ 'upl.format.file' | t }}</h2>
    <div class="upl-row">
      <div class="form-group">
        <label class="form-label" for="upl-file-kind">{{ 'upl.format.field.file_kind' | t }}</label>
        <smt-select
          class="upl-select"
          smtTriggerId="upl-file-kind"
          data-testid="upl-file-kind"
          [disabled]="!editable()"
          [options]="fileKindOptions()"
          [allowClear]="false"
          [value]="model().fileKind"
          (valueChange)="onFileKindChange($event)"
        ></smt-select>
      </div>
      @if (model().fileKind === 'csv') {
        <div class="form-group">
          <label class="form-label" for="upl-encoding">{{ 'upl.format.field.encoding' | t }}</label>
          <smt-select
            class="upl-select"
            smtTriggerId="upl-encoding"
            data-testid="upl-encoding"
            [disabled]="!editable()"
            [options]="encodingOptions()"
            [allowClear]="false"
            [(ngModel)]="model().encoding"
            [ngModelOptions]="{ standalone: true }"
          ></smt-select>
        </div>
        <div class="form-group">
          <label class="form-label" for="upl-delimiter">{{ 'upl.format.field.delimiter' | t }}</label>
          <smt-input
            class="upl-input-tiny"
            smtFieldId="upl-delimiter"
            [maxLength]="1"
            [disabled]="!editable()"
            [(ngModel)]="model().delimiter"
            [ngModelOptions]="{ standalone: true }" />
        </div>
      }
      <div class="form-group">
        <label class="form-label" for="upl-match-by">{{ 'upl.format.field.match_by' | t }}</label>
        <smt-select
          class="upl-select"
          smtTriggerId="upl-match-by"
          data-testid="upl-match-by"
          [disabled]="!editable()"
          [options]="matchByOptions()"
          [allowClear]="false"
          [(ngModel)]="model().matchColumnsBy"
          [ngModelOptions]="{ standalone: true }"
        ></smt-select>
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 0.75rem; }
    .upl-block-title { margin: 0; font-size: 1rem; color: var(--text-main); }
    .upl-row { display: flex; flex-wrap: wrap; gap: 1rem; align-items: flex-start; }
    .upl-input-tiny { max-width: 4rem; }
    .upl-select { min-width: 12rem; }
  `]
})
export class FormatFileStepComponent {
  private readonly i18n = inject(I18nService);

  readonly model = input.required<UplFormatDraftRequest>();

  readonly editable = input(false);

  private readonly fileKindMemo = optionsMemo<SMTSelectOption<UplFileKind>[]>();
  private readonly encodingMemo = optionsMemo<SMTSelectOption<UplEncoding>[]>();
  private readonly matchByMemo = optionsMemo<SMTSelectOption<UplMatchBy>[]>();

  fileKindOptions(): SMTSelectOption<UplFileKind>[] {
    return this.fileKindMemo([this.i18n.currentLang()], () =>
      UPL_FILE_KINDS.map(kind => ({ id: kind, label: this.i18n.translate(UPL_FILE_KIND_KEY[kind]) })));
  }

  encodingOptions(): SMTSelectOption<UplEncoding>[] {
    return this.encodingMemo([this.i18n.currentLang()], () =>
      UPL_ENCODINGS.map(encoding => ({ id: encoding, label: this.i18n.translate(UPL_ENCODING_KEY[encoding]) })));
  }

  matchByOptions(): SMTSelectOption<UplMatchBy>[] {
    return this.matchByMemo([this.i18n.currentLang()], () =>
      UPL_MATCH_BY.map(match => ({ id: match, label: this.i18n.translate(UPL_MATCH_BY_KEY[match]) })));
  }

  onFileKindChange(kind: UplFileKind | null): void {
    if (kind === null) return;
    const model = this.model();
    model.fileKind = kind;
    if (kind === 'csv') {
      if (!isFilled(model.encoding)) model.encoding = 'utf-8';
      if (!isFilled(model.delimiter)) model.delimiter = ';';
    }
  }
}
