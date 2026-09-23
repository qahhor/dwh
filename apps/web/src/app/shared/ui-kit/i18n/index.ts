/* Adapter, not vendored code.
 *
 * The vendored table reads its strings from `i18n.messages`. This
 * application already has an I18nService with a server-backed catalogue, so
 * rather than carrying the kit's parallel translation system this exposes
 * the shape the table binds to, filled from ours. */
import { Injectable, computed, inject } from '@angular/core';
import { I18nService } from '../../../core/services/i18n.service';

export interface SMTTableMessages {
  readonly table: { readonly noResults: string };
  readonly dataTable: { readonly selectAll: string; readonly selectRow: string };
  readonly tree: { readonly expandAll: string; readonly collapseAll: string };
}

@Injectable({ providedIn: 'root' })
export class SMTI18nService {
  private readonly i18n = inject(I18nService);

  readonly messages = computed<SMTTableMessages>(() => ({
    table: { noResults: this.i18n.translate('ui.table.nichego_ne_naydeno') },
    dataTable: {
      selectAll: this.i18n.translate('ui.table.vybrat_vse'),
      selectRow: this.i18n.translate('ui.table.vybrat_stroku'),
    },
    tree: {
      expandAll: this.i18n.translate('ui.tree.razvernut_vse'),
      collapseAll: this.i18n.translate('ui.tree.svernut_vse'),
    },
  }));
}
