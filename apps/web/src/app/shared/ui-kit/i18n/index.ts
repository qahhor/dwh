/* Adapter, not vendored code.
 *
 * The vendored components read their strings from `i18n.messages`. This
 * application already has an I18nService with a server-backed catalogue, so
 * rather than carrying the kit's parallel translation system this exposes
 * the shape the components bind to, filled from ours. */
import { Injectable, computed, inject } from '@angular/core';
import { I18nService } from '../../../core/services/i18n.service';
import type { SMTControlMessages } from '../components/forms/control/control-messages';

export interface SMTMessages {
  readonly control: SMTControlMessages;
  readonly common: { readonly close: string; readonly cancel: string };
  readonly table: { readonly noResults: string };
  readonly dataTable: { readonly selectAll: string; readonly selectRow: string };
  readonly tree: { readonly expandAll: string; readonly collapseAll: string };
  readonly modalConfirm: { readonly title: string; readonly yes: string; readonly no: string };
}

@Injectable({ providedIn: 'root' })
export class SMTI18nService {
  private readonly i18n = inject(I18nService);

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
