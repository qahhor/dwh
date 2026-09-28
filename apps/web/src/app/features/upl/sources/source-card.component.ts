import { ChangeDetectionStrategy, Component, computed, inject, Signal, TemplateRef, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormField } from '@angular/forms/signals';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import {
  UPL_PERIODICITIES,
  UPL_STRICTNESSES,
  UplPeriodicity,
  UplSource,
  UplStrictness,
  UplVersionItem,
  UplVersionStatus,
} from '../upl-api';
import { UPL_PERIODICITY_KEY, UPL_STRICTNESS_KEY, UPL_VERSION_STATUS_KEY } from '../upl-labels';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { DraftMode, SourceCardStore } from './source-card.store';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

@Component({
  selector: 'app-upl-source-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTAlertComponent,
    SMTControlComponent,
    UiLocalTableComponent,
    FormField,
    RouterLink,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTBadgeComponent,
    SMTRadioGroupComponent,
    DatePipe,
  ],
  providers: [SourceCardStore],
  templateUrl: './source-card.component.html',
  styleUrl: './source-card.component.css',
})
export class SourceCardComponent {
  /** The source, its versions and the card's flows; the template reads it directly. */
  readonly store = inject(SourceCardStore);
  private readonly i18n = inject(I18nService);
  private readonly route = inject(ActivatedRoute);

  private readonly versionCell = viewChild.required<TemplateRef<unknown>>('versionCell');
  private readonly versionStatusCell = viewChild.required<TemplateRef<unknown>>('versionStatusCell');
  private readonly validFromCell = viewChild.required<TemplateRef<unknown>>('validFromCell');
  private readonly validToCell = viewChild.required<TemplateRef<unknown>>('validToCell');
  private readonly publishedCell = viewChild.required<TemplateRef<unknown>>('publishedCell');
  private readonly templateCell = viewChild.required<TemplateRef<unknown>>('templateCell');

  readonly versionsConfig = computed<TableConfig<UplVersionItem>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, v) => v.version,
      ariaLabel: this.i18n.translate('upl.version.title'),
      layout: 'fit',
      columns: {
        version: { header: header('upl.version.col.version'), content: cell(this.versionCell), width: '100px' },
        status: { header: header('upl.version.col.status'), content: cell(this.versionStatusCell), width: '150px' },
        validFrom: { header: header('upl.version.col.valid_from'), content: cell(this.validFromCell), width: '140px' },
        validTo: { header: header('upl.version.col.valid_to'), content: cell(this.validToCell), width: '140px' },
        published: { header: header('upl.version.col.published'), content: cell(this.publishedCell) },
        template: { header: header('upl.template.column'), content: cell(this.templateCell), width: '170px' },
      },
      columnsOrder: ['version', 'status', 'validFrom', 'validTo', 'published', 'template'],
    };
  });

  /** A copy is offered only when there is a version to copy. */
  readonly draftModes = computed<SMTRadioOption<DraftMode>[]>(() => [
    { value: 'empty', label: this.i18n.translate('upl.version.draft_empty') },
    ...(this.store.copyCandidates().length > 0
      ? [{ value: 'copy' as const, label: this.i18n.translate('upl.version.draft_copy') }]
      : []),
  ]);

  /** Versions a draft can be copied from, newest first. */
  readonly copyFromOptions = computed<SMTSelectOption<number>[]>(() =>
    this.store.copyCandidates().map((v) => ({ id: v.version, label: String(v.version) })),
  );
  private readonly periodicityMemo = optionsMemo<SMTSelectOption<UplPeriodicity>[]>();
  private readonly strictnessMemo = optionsMemo<SMTSelectOption<UplStrictness>[]>();
  readonly versionStatusKey = UPL_VERSION_STATUS_KEY;
  readonly dash = '—';

  /** All versions of a source are loaded, so a header click sorts them all. */
  readonly versionSortValues = {
    version: (v: UplVersionItem) => v.version,
    status: (v: UplVersionItem) => v.status,
    validFrom: (v: UplVersionItem) => v.validFrom,
    validTo: (v: UplVersionItem) => v.validTo,
    published: (v: UplVersionItem) => v.publishedAt,
  };

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => this.store.open(params.get('id')));
  }

  /** Periodicities of a source; translated again when the language changes. */
  periodicityOptions(): SMTSelectOption<UplPeriodicity>[] {
    return this.periodicityMemo([this.i18n.currentLang()], () =>
      UPL_PERIODICITIES.map((p) => ({ id: p, label: this.i18n.translate(UPL_PERIODICITY_KEY[p]) })),
    );
  }

  /** Reconciliation strictness levels; translated again when the language changes. */
  strictnessOptions(): SMTSelectOption<UplStrictness>[] {
    return this.strictnessMemo([this.i18n.currentLang()], () =>
      UPL_STRICTNESSES.map((st) => ({ id: st, label: this.i18n.translate(UPL_STRICTNESS_KEY[st]) })),
    );
  }

  /** The translated message for a field's error code, or nothing; smt-control links it to the field. */
  fieldErrorText(key: string): string {
    const code = this.store.fieldErrors()[key];
    return code ? this.i18n.translate(code) : '';
  }

  /** The supplier's file for a version, in the reader's language; the browser downloads it with the session cookie. */
  templateUrl(version: number): string {
    const id = encodeURIComponent(this.store.sourceId() ?? '');
    const lang = encodeURIComponent(this.i18n.currentLang());
    return `/api/v1/upl/sources/${id}/format-versions/${version}/template?lang=${lang}`;
  }

  activeVersionLabel(source: UplSource): string {
    return this.i18n.translate('upl.card.active_version', { version: source.lastPublishedVersion ?? 0 });
  }

  statusKeyOf(version: UplVersionItem): string {
    return this.versionStatusKey[version.status];
  }

  statusVariant(status: UplVersionStatus): TBadgeVariant {
    if (status === 'published') {
      return 'success';
    }
    return status === 'draft' ? 'blue' : 'gray';
  }
}
