import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CustomNavigationItem, NavigationTargetType } from '../../../../core/models/navigation.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../../shared/ui-kit/components/modal';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-navigation-settings-modal',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, SMTSelectComponent,
    CommonModule,
    FormsModule,
    SMTDialogComponent, SMTDialogContentDirective,
    SMTButtonComponent,
    TranslatePipe
  ],
  template: `
    <!-- Create/Edit Modal -->
    <smt-dialog
      *ngIf="isModalOpen"
      [open]="isModalOpen"
      [smtTitle]="editingItem ? ('nav.settings.edit_modal_title' | t) : ('nav.settings.create_modal_title' | t)"
      (closed)="closeModal.emit()">
      <ng-template smtDialogContent>
      <div class="modal-form">
        <div class="form-row">
          <div class="form-group flex-2">
            <label class="form-label" for="nav-title">{{ 'nav.settings.field_title' | t }} *</label>
            <smt-input
              smtFieldId="nav-title"
              [ngModel]="formTitle"
              (ngModelChange)="formTitleChange.emit($event); titleChange.emit()"
              [placeholder]="'nav.settings.title_placeholder' | t" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label" for="nav-code">{{ 'nav.settings.field_code' | t }} *</label>
            <smt-input
              smtFieldId="nav-code"
              [ngModel]="formCode"
              (ngModelChange)="formCodeChange.emit($event)"
              placeholder="superset-sales" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label" for="nav-type">{{ 'nav.settings.field_type' | t }}</label>
            <smt-select smtTriggerId="nav-type" [options]="targetTypeOptions()" [allowClear]="false"
              [value]="formTargetType" (valueChange)="$event && formTargetTypeChange.emit($event)" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label" for="nav-section">{{ 'nav.settings.field_section' | t }}</label>
            <smt-select smtTriggerId="nav-section" [options]="sectionOptions()" [allowClear]="false"
              [value]="formSectionId" (valueChange)="$event && formSectionIdChange.emit($event)" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label" for="nav-order">{{ 'nav.settings.field_order' | t }}</label>
            <smt-input
              smtFieldId="nav-order"
              type="number"
              [ngModel]="formSortOrder"
              (ngModelChange)="formSortOrderChange.emit($event)" />
          </div>
        </div>

        <div *ngIf="formTargetType === 'EMBEDDED_IFRAME'" class="type-hint-box">
          <span class="material-symbols-outlined hint-icon" aria-hidden="true">info</span>
          <span>{{ 'nav.settings.iframe_type_hint' | t }}</span>
        </div>

        <div class="form-group">
          <label class="form-label" for="nav-url">{{ 'nav.settings.field_url' | t }} *</label>
          <smt-input
            smtFieldId="nav-url"
            [ngModel]="formUrl"
            (ngModelChange)="formUrlChange.emit($event)"
            (touch)="urlBlur.emit()"
            placeholder="https://bi.company.uz/superset/dashboard/123/" />
          <span class="form-hint">{{ 'nav.settings.url_hint' | t }}</span>
        </div>

        <div class="form-group">
          <label class="form-label">{{ 'nav.settings.field_icon' | t }}</label>
          <div class="icon-selector-row">
            <smt-input
              class="icon-input"
              [ngModel]="formIcon"
              (ngModelChange)="formIconChange.emit($event)"
              placeholder="analytics" />
            <span class="material-symbols-outlined icon-preview" aria-hidden="true">{{ formIcon || 'bar_chart' }}</span>
          </div>
          <div class="icon-quick-chips">
            <button
              *ngFor="let ic of popularIcons"
              type="button"
              class="chip-btn"
              [class.active]="formIcon === ic"
              (click)="formIconChange.emit(ic)"
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{ ic }}</span>
            </button>
          </div>
        </div>
      </div>

      <div footer class="modal-footer-btns">
        <button smt-button type="button" smtVariant="secondary" (click)="closeModal.emit()">{{ 'common.cancel' | t }}</button>
        <button smt-button type="button" smtVariant="primary" [smtLoading]="isSubmitting" (click)="saveItem.emit()" [disabled]="!isFormValid">
          {{ 'common.save' | t }}
        </button>
      </div>
      </ng-template>
    </smt-dialog>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 8px 0;
    }

    .form-row {
      display: flex;
      gap: 12px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .flex-1 { flex: 1; }
    .flex-2 { flex: 2; }

    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }



    .form-hint {
      font-size: 11px;
      color: var(--text-muted);
    }

    .type-hint-box {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      background: var(--info-bg);
      border: 1px solid var(--info-border);
      color: var(--info-text);
      font-size: 12px;
      line-height: 1.4;
    }

    .hint-icon {
      font-size: 18px;
      flex-shrink: 0;
      color: var(--info-text);
    }

    .icon-selector-row {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .icon-input {
      flex: 1;
    }

    .icon-preview {
      font-size: 24px;
      color: var(--primary);
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--bg-hover);
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }

    .icon-quick-chips {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
      margin-top: 4px;
    }

    .chip-btn {
      width: 32px;
      height: 32px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background: var(--bg-surface);
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.12s ease;
    }

    .chip-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    .chip-btn.active {
      background: var(--primary);
      color: var(--on-primary);
      border-color: var(--primary);
    }

    .chip-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .modal-footer-btns {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  `]
})
export class NavigationSettingsModalComponent {
  private readonly i18n = inject(I18nService);

  @Input() isModalOpen = false;
  @Input() editingItem: CustomNavigationItem | null = null;
  @Input() isSubmitting = false;
  @Input() isFormValid = false;

  @Input() formTitle = '';
  @Input() formCode = '';
  @Input() formTargetType: NavigationTargetType = 'EMBEDDED_IFRAME';
  @Input() formSectionId = 'custom';
  @Input() formSortOrder = 10;
  @Input() formUrl = '';
  @Input() formIcon = 'analytics';
  @Input() popularIcons: string[] = [];

  @Output() formTitleChange = new EventEmitter<string>();
  @Output() formCodeChange = new EventEmitter<string>();
  @Output() formTargetTypeChange = new EventEmitter<NavigationTargetType>();
  @Output() formSectionIdChange = new EventEmitter<string>();
  @Output() formSortOrderChange = new EventEmitter<number>();
  @Output() formUrlChange = new EventEmitter<string>();
  @Output() formIconChange = new EventEmitter<string>();

  @Output() titleChange = new EventEmitter<void>();
  @Output() urlBlur = new EventEmitter<void>();
  @Output() closeModal = new EventEmitter<void>();
  @Output() saveItem = new EventEmitter<void>();

  private readonly targetTypeMemo = optionsMemo<SMTSelectOption<NavigationTargetType>[]>();

  private readonly sectionMemo = optionsMemo<SMTSelectOption<string>[]>();

  targetTypeOptions(): SMTSelectOption<NavigationTargetType>[] {
    return this.targetTypeMemo([this.i18n.currentLang()], () => [
      { id: 'EMBEDDED_IFRAME', label: this.i18n.translate('nav.settings.type_embedded') },
      { id: 'EXTERNAL_LINK', label: this.i18n.translate('nav.settings.type_external') },
      { id: 'INTERNAL_ROUTE', label: this.i18n.translate('nav.settings.type_internal') },
    ]);
  }

  sectionOptions(): SMTSelectOption<string>[] {
    return this.sectionMemo([this.i18n.currentLang()], () => [
      { id: 'custom', label: this.i18n.translate('nav.settings.section_custom') },
      { id: 'workspace', label: this.i18n.translate('nav.section.workspace') },
      { id: 'iam', label: this.i18n.translate('nav.section.iam') },
      { id: 'administration', label: this.i18n.translate('nav.section.administration') },
    ]);
  }
}
