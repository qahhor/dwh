import { ChangeDetectionStrategy, Component, inject, input, model } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import {
  UPL_ENCODINGS,
  UPL_FILE_KINDS,
  UPL_MATCH_BY,
  UplEncoding,
  UplFileKind,
  UplFormatDraftRequest,
  UplMatchBy,
} from '../upl.api';
import { UPL_ENCODING_KEY, UPL_FILE_KIND_KEY, UPL_MATCH_BY_KEY } from '../upl-labels';
import { isFilled } from './upl-format-model';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { UplFieldError, touchUplField, uplFieldErrorText, uplSameField } from './upl-format-errors';

/** The "File" step of the file format: file kind, CSV encoding and delimiter, column mapping. Edits the model. */
@Component({
  selector: 'app-upl-format-file-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTControlComponent, SMTInputComponent, SMTSelectComponent, TranslatePipe],
  template: `
    <h2 class="upl-block-title">{{ 'upl.format.file' | t }}</h2>
    <div class="upl-row">
      <smt-control class="form-group" [smtLabel]="'upl.format.field.file_kind' | t" [smtError]="fieldError('fileKind')">
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
      </smt-control>
      @if (model().fileKind === 'csv') {
        <smt-control
          class="form-group"
          [smtLabel]="'upl.format.field.encoding' | t"
          [smtError]="fieldError('encoding')"
        >
          <smt-select
            class="upl-select"
            smtTriggerId="upl-encoding"
            data-testid="upl-encoding"
            [disabled]="!editable()"
            [options]="encodingOptions()"
            [allowClear]="false"
            [(value)]="model().encoding"
            (valueChange)="clearFieldError('encoding')"
          ></smt-select>
        </smt-control>
        <smt-control
          class="form-group"
          [smtLabel]="'upl.format.field.delimiter' | t"
          [smtError]="fieldError('delimiter')"
        >
          <smt-input
            class="upl-input-tiny"
            smtFieldId="upl-delimiter"
            [maxLength]="1"
            [disabled]="!editable()"
            [(value)]="model().delimiter"
            (edited)="clearFieldError('delimiter')"
            (touch)="touchField('delimiter')"
          />
        </smt-control>
      }
      <smt-control
        class="form-group"
        [smtLabel]="'upl.format.field.match_by' | t"
        [smtError]="fieldError('matchColumnsBy')"
      >
        <smt-select
          class="upl-select"
          smtTriggerId="upl-match-by"
          data-testid="upl-match-by"
          [disabled]="!editable()"
          [options]="matchByOptions()"
          [allowClear]="false"
          [(value)]="model().matchColumnsBy"
          (valueChange)="clearFieldError('matchColumnsBy')"
        ></smt-select>
      </smt-control>
    </div>
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }
      .upl-block-title {
        margin: 0;
        font-size: 1rem;
        color: var(--text-main);
      }
      .upl-row {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: flex-start;
      }
      .upl-input-tiny {
        max-width: 4rem;
      }
      .upl-select {
        min-width: 12rem;
      }
    `,
  ],
})
export class FormatFileStepComponent {
  private readonly i18n = inject(I18nService);

  readonly model = input.required<UplFormatDraftRequest>();

  readonly editable = input(false);
  readonly errors = model<UplFieldError[]>([]);

  private readonly fileKindMemo = optionsMemo<SMTSelectOption<UplFileKind>[]>();
  private readonly encodingMemo = optionsMemo<SMTSelectOption<UplEncoding>[]>();
  private readonly matchByMemo = optionsMemo<SMTSelectOption<UplMatchBy>[]>();

  fieldError(field: string): string {
    const error = this.errors().find((item) => uplSameField(item, field));
    return error ? uplFieldErrorText(error, (key) => this.i18n.translate(key)) : '';
  }

  clearFieldError(field: string): void {
    this.errors.update((errors) => errors.filter((error) => !uplSameField(error, field)));
  }

  touchField(field: string): void {
    this.errors.update((errors) => touchUplField(this.model(), errors, field));
  }

  fileKindOptions(): SMTSelectOption<UplFileKind>[] {
    return this.fileKindMemo([this.i18n.currentLang()], () =>
      UPL_FILE_KINDS.map((kind) => ({ id: kind, label: this.i18n.translate(UPL_FILE_KIND_KEY[kind]) })),
    );
  }

  encodingOptions(): SMTSelectOption<UplEncoding>[] {
    return this.encodingMemo([this.i18n.currentLang()], () =>
      UPL_ENCODINGS.map((encoding) => ({ id: encoding, label: this.i18n.translate(UPL_ENCODING_KEY[encoding]) })),
    );
  }

  matchByOptions(): SMTSelectOption<UplMatchBy>[] {
    return this.matchByMemo([this.i18n.currentLang()], () =>
      UPL_MATCH_BY.map((match) => ({ id: match, label: this.i18n.translate(UPL_MATCH_BY_KEY[match]) })),
    );
  }

  onFileKindChange(kind: UplFileKind | null): void {
    if (kind === null) return;
    const model = this.model();
    model.fileKind = kind;
    this.clearFieldError('fileKind');
    this.clearFieldError('encoding');
    this.clearFieldError('delimiter');
    if (kind === 'csv') {
      if (!isFilled(model.encoding)) model.encoding = 'utf-8';
      if (!isFilled(model.delimiter)) model.delimiter = ';';
    }
  }
}
