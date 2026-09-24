/* Adapter, not vendored code.
 *
 * The vendored components read their strings from `i18n.messages`. This
 * application already has an I18nService with a server-backed catalogue, so
 * rather than carrying the kit's parallel translation system this exposes
 * the shape the components bind to, filled from ours. */
import { Injectable, computed, inject } from '@angular/core';
import { I18nService, LANGUAGE_LOCALES } from '../../../core/services/i18n.service';
import type { SMTControlMessages } from '../components/forms/control/control-messages';
import type { DateRangePresetKey } from '../components/forms/date-picker/date-utils';

export interface SMTDateMessages {
  readonly placeholder: string;
  readonly openCalendar: string;
  readonly dialogLabel: string;
  readonly rangeDialogLabel: string;
  readonly previousMonth: string;
  readonly nextMonth: string;
  readonly time: string;
  readonly invalid: (format: string) => string;
  readonly apply: string;
  readonly anyPeriod: string;
  readonly clearPeriod: string;
  readonly presets: string;
  readonly preset: Readonly<Record<DateRangePresetKey, string>>;
}

export interface SMTSelectMessages {
  readonly placeholder: string;
  readonly name: string;
  readonly search: string;
  readonly clearSearch: string;
  readonly clear: string;
  readonly none: string;
  readonly noResults: string;
  readonly loading: string;
  readonly loadFailed: string;
  readonly retry: string;
  readonly loadMore: string;
}

export interface SMTMessages {
  readonly control: SMTControlMessages;
  readonly select: SMTSelectMessages;
  readonly date: SMTDateMessages;
  readonly common: { readonly close: string; readonly cancel: string };
  readonly table: { readonly noResults: string };
  readonly dataTable: { readonly selectAll: string; readonly selectRow: string };
  readonly tree: { readonly expandAll: string; readonly collapseAll: string };
  readonly modalConfirm: { readonly title: string; readonly yes: string; readonly no: string };
}

@Injectable({ providedIn: 'root' })
export class SMTI18nService {
  private readonly i18n = inject(I18nService);

  /** Product language code (`ru`, `en`, …) and its Intl locale. */
  readonly language = computed(() => this.i18n.currentLang());

  readonly locale = computed(() => LANGUAGE_LOCALES[this.language()] ?? this.language());

  readonly messages = computed<SMTMessages>(() => ({
    control: {
      required: this.i18n.translate('ui.control.required'),
      email: this.i18n.translate('ui.control.email'),
      pattern: this.i18n.translate('ui.control.pattern'),
      parse: this.i18n.translate('ui.control.parse'),
      invalid: this.i18n.translate('ui.control.invalid'),
      minLength: count => this.i18n.translate('ui.control.min_length', { count }),
      maxLength: count => this.i18n.translate('ui.control.max_length', { count }),
      min: value => this.i18n.translate('ui.control.min', { value }),
      max: value => this.i18n.translate('ui.control.max', { value }),
    },
    select: {
      placeholder: this.i18n.translate('ui.searchable_select.vyberite_iz_spiska'),
      name: this.i18n.translate('ui.searchable_select.vybor_znacheniya'),
      search: this.i18n.translate('ui.searchable_select.poisk_po_variantam'),
      clearSearch: this.i18n.translate('ui.searchable_select.ochistit_poisk'),
      clear: this.i18n.translate('ui.searchable_select.ochistit_vybor'),
      none: this.i18n.translate('ui.searchable_select.ne_vybrano_snyat_vybor'),
      noResults: this.i18n.translate('ui.searchable_select.nichego_ne_naydeno'),
      loading: this.i18n.translate('common.loading'),
      loadFailed: this.i18n.translate('ui.remote_lookup.failed'),
      retry: this.i18n.translate('common.retry'),
      loadMore: this.i18n.translate('common.load_more'),
    },
    date: {
      placeholder: this.i18n.translate('ui.date.placeholder'),
      openCalendar: this.i18n.translate('ui.date.open_calendar'),
      dialogLabel: this.i18n.translate('ui.date.dialog_label'),
      rangeDialogLabel: this.i18n.translate('ui.date.range_dialog_label'),
      previousMonth: this.i18n.translate('ui.date.previous_month'),
      nextMonth: this.i18n.translate('ui.date.next_month'),
      time: this.i18n.translate('ui.date.time'),
      invalid: format => this.i18n.translate('ui.date.invalid', { format }),
      apply: this.i18n.translate('ui.date.apply'),
      anyPeriod: this.i18n.translate('ui.date.any_period'),
      clearPeriod: this.i18n.translate('ui.date.clear_period'),
      presets: this.i18n.translate('ui.date.presets'),
      preset: {
        today: this.i18n.translate('ui.date.preset.today'),
        yesterday: this.i18n.translate('ui.date.preset.yesterday'),
        last7: this.i18n.translate('ui.date.preset.last7'),
        last30: this.i18n.translate('ui.date.preset.last30'),
        thisMonth: this.i18n.translate('ui.date.preset.this_month'),
        lastMonth: this.i18n.translate('ui.date.preset.last_month'),
        thisYear: this.i18n.translate('ui.date.preset.this_year'),
      },
    },
    common: {
      close: this.i18n.translate('common.close'),
      cancel: this.i18n.translate('common.cancel'),
    },
    table: { noResults: this.i18n.translate('ui.table.nichego_ne_naydeno') },
    dataTable: {
      selectAll: this.i18n.translate('ui.table.vybrat_vse'),
      selectRow: this.i18n.translate('ui.table.vybrat_stroku'),
    },
    tree: {
      expandAll: this.i18n.translate('ui.tree.razvernut_vse'),
      collapseAll: this.i18n.translate('ui.tree.svernut_vse'),
    },
    modalConfirm: {
      title: this.i18n.translate('ui.modal.confirm_title'),
      yes: this.i18n.translate('common.yes'),
      no: this.i18n.translate('common.no'),
    },
  }));
}
