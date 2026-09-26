import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, I18nService } from '../../../../core/services/i18n.service';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-custom-fields-toolbar',
  standalone: true,
  imports: [
    SMTRadioGroupComponent, SMTInputComponent, CommonModule,
    FormsModule,
    TranslatePipe
  ],
  template: `
    <div class="toolbar-container">
      <!-- Entity Type Filter Tabs -->
      <smt-radio-group
        smtAppearance="chips"
        class="entity-filter"
        [options]="entityOptions()"
        [value]="selectedEntity"
        [smtAriaLabel]="'iam.filtr_po_tipu_suschnosti' | t"
        (valueChange)="entityChange.emit($event ?? selectedEntity)" />

      <!-- Quick Search -->
      <!-- The clear button empties the field through valueChange, so the query resets like the old button did. -->
      <smt-input
        class="search-box"
        type="search"
        smtIcon="search"
        clearable
        [value]="searchQuery"
        (valueChange)="searchQueryChange.emit($event === null ? '' : '' + $event)"
        [placeholder]="'iam.poisk_poley' | t"
        [smtAriaLabel]="'iam.poisk_poley' | t" />
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .toolbar-container {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      margin-bottom: 20px;
      flex-wrap: wrap;
    }








    .search-box {
      width: auto;
      min-width: 240px;
    }

    @media (max-width: 640px) {
      .toolbar-container {
        flex-direction: column;
        align-items: stretch;
      }
      .search-box {
        min-width: 100%;
      }
    }
  `]
})
export class CustomFieldsToolbarComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  private readonly uiI18n = inject(I18nService);

  @Input() availableEntities: string[] = ['ALL', 'USER', 'PROJECT', 'TASK', 'NOTE'];
  @Input() selectedEntity = 'ALL';
  @Input() searchQuery = '';
  @Input() entityCounts: Record<string, number> = {};

  @Output() entityChange = new EventEmitter<string>();
  @Output() searchQueryChange = new EventEmitter<string>();
  @Output() clearSearch = new EventEmitter<void>();

  private readonly entityMemo = optionsMemo<SMTRadioOption<string>[]>();

  getEntityLabel(ent: string): string {
    switch (ent) {
      case 'ALL': return this.uiI18n.translate('iam.vse_suschnosti');
      case 'USER': return this.uiI18n.translate('nav.users');
      case 'PROJECT': return this.uiI18n.translate('nav.projects');
      case 'TASK': return this.uiI18n.translate('nav.tasks');
      case 'NOTE': return this.uiI18n.translate('iam.zametka_note');
      default: return ent;
    }
  }

  getEntityIcon(ent: string): string {
    switch (ent.toUpperCase()) {
      case 'ALL': return 'apps';
      case 'USER': return 'person';
      case 'PROJECT': return 'folder';
      case 'TASK': return 'task_alt';
      case 'NOTE': return 'description';
      case 'ORGANIZATION_UNIT': return 'corporate_fare';
      default: return 'data_object';
    }
  }

  /** Entity types as chips with their icon and how many fields each has. */
  entityOptions(): SMTRadioOption<string>[] {
    return this.entityMemo([this.availableEntities, this.entityCounts, this.optionText.currentLang()], () => this.availableEntities.map(entity => ({
      value: entity,
      label: this.getEntityLabel(entity),
      icon: this.getEntityIcon(entity),
      count: this.entityCounts[entity] || 0,
    })));
  }
}
