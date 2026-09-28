import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { CustomNavigationItem, NavigationPermissionChoice, NavigationTargetType } from '@core/models/navigation.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-navigation-settings-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    TranslatePipe,
  ],
  template: `
    <!-- Create/Edit Modal -->
    @if (isModalOpen()) {
      <smt-dialog
        [open]="isModalOpen()"
        [smtTitle]="editingItem() ? ('nav.settings.edit_modal_title' | t) : ('nav.settings.create_modal_title' | t)"
        (closed)="closeModal.emit()"
      >
        <ng-template smtDialogContent>
          <div class="modal-form">
            <div class="form-row">
              <div class="form-group flex-2">
                <label class="form-label" for="nav-title">{{ 'nav.settings.field_title' | t }} *</label>
                <!-- (edited) reports every keystroke: each one derives the code from the title again. -->
                <smt-input
                  smtFieldId="nav-title"
                  [value]="formTitle()"
                  (edited)="formTitleChange.emit(asText($event)); titleChange.emit()"
                  [placeholder]="'nav.settings.title_placeholder' | t"
                />
              </div>
              <div class="form-group flex-1">
                <label class="form-label" for="nav-code">{{ 'nav.settings.field_code' | t }} *</label>
                <smt-input
                  smtFieldId="nav-code"
                  [value]="formCode()"
                  (valueChange)="formCodeChange.emit(asText($event))"
                  placeholder="superset-sales"
                />
              </div>
            </div>

            <div class="form-row">
              <div class="form-group flex-1">
                <label class="form-label" for="nav-type">{{ 'nav.settings.field_type' | t }}</label>
                <smt-select
                  smtTriggerId="nav-type"
                  [options]="targetTypeOptions()"
                  [allowClear]="false"
                  [value]="formTargetType()"
                  (valueChange)="$event && formTargetTypeChange.emit($event)"
                />
              </div>
              <div class="form-group flex-1">
                <label class="form-label" for="nav-section">{{ 'nav.settings.field_section' | t }}</label>
                <smt-select
                  smtTriggerId="nav-section"
                  [options]="sectionOptions()"
                  [allowClear]="false"
                  [value]="formSectionId()"
                  (valueChange)="$event && formSectionIdChange.emit($event)"
                />
              </div>
              <div class="form-group flex-1">
                <label class="form-label" for="nav-order">{{ 'nav.settings.field_order' | t }}</label>
                <smt-input
                  smtFieldId="nav-order"
                  type="number"
                  [value]="formSortOrder()"
                  (valueChange)="formSortOrderChange.emit(asNumber($event))"
                />
              </div>
            </div>

            @if (formTargetType() === 'EMBEDDED_IFRAME') {
              <div class="type-hint-box">
                <span class="material-symbols-outlined hint-icon" aria-hidden="true">info</span>
                <span>{{ 'nav.settings.iframe_type_hint' | t }}</span>
              </div>
            }

            <div class="form-group">
              <label class="form-label" for="nav-url">{{ 'nav.settings.field_url' | t }} *</label>
              <smt-input
                smtFieldId="nav-url"
                [value]="formUrl()"
                (valueChange)="formUrlChange.emit(asText($event))"
                (touch)="urlBlur.emit()"
                placeholder="https://bi.company.uz/superset/dashboard/123/"
              />
              <span class="form-hint">{{ 'nav.settings.url_hint' | t }}</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="nav-permission">{{ 'nav.settings.field_permission' | t }}</label>
              <smt-select
                smtTriggerId="nav-permission"
                [options]="permissionOptions()"
                [allowClear]="true"
                smtDescribedBy="nav-permission-hint"
                [placeholder]="'nav.settings.permission_everyone' | t"
                [value]="formRequiredPermission()"
                (valueChange)="formRequiredPermissionChange.emit($event ?? null)"
              />
              <span class="form-hint" id="nav-permission-hint">{{ 'nav.settings.permission_hint' | t }}</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="nav-icon">{{ 'nav.settings.field_icon' | t }}</label>
              <div class="icon-selector-row">
                <smt-input
                  smtFieldId="nav-icon"
                  class="icon-input"
                  [value]="formIcon()"
                  (valueChange)="formIconChange.emit(asText($event))"
                  placeholder="analytics"
                />
                <span class="material-symbols-outlined icon-preview" aria-hidden="true">{{
                  formIcon() || 'bar_chart'
                }}</span>
              </div>
              <div class="icon-quick-chips">
                @for (ic of popularIcons(); track ic) {
                  <button
                    type="button"
                    class="chip-btn"
                    [class.active]="formIcon() === ic"
                    (click)="formIconChange.emit(ic)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">{{ ic }}</span>
                  </button>
                }
              </div>
            </div>
          </div>

          <div footer class="modal-footer-btns">
            <button smt-button type="button" smtVariant="secondary" (click)="closeModal.emit()">
              {{ 'common.cancel' | t }}
            </button>
            <button
              smt-button
              type="button"
              smtVariant="primary"
              [smtLoading]="isSubmitting()"
              (click)="saveItem.emit()"
              [disabled]="!isFormValid()"
            >
              {{ 'common.save' | t }}
            </button>
          </div>
        </ng-template>
      </smt-dialog>
    }
  `,
  styleUrl: './navigation-settings-modal.component.css',
})
export class NavigationSettingsModalComponent {
  private readonly i18n = inject(I18nService);

  readonly editingItem = input<CustomNavigationItem | null>(null);
  readonly isSubmitting = input(false);
  readonly isFormValid = input(false);

  readonly formTitle = input('');
  readonly formCode = input('');
  readonly formTargetType = input<NavigationTargetType>('EMBEDDED_IFRAME');
  readonly formSectionId = input('custom');
  /** Null while the order field is empty, as the number field gives it. */
  readonly formSortOrder = input<number | null>(10);
  readonly formUrl = input('');
  readonly formIcon = input('analytics');
  readonly popularIcons = input<string[]>([]);

  readonly isModalOpen = input(false);
  /** `form.action` pair the item is limited to; null shows it to everyone. */
  readonly formRequiredPermission = input<string | null>(null);
  readonly permissionChoices = input<NavigationPermissionChoice[]>([]);

  readonly formTitleChange = output<string>();
  readonly formCodeChange = output<string>();
  readonly formTargetTypeChange = output<NavigationTargetType>();
  readonly formSectionIdChange = output<string>();
  readonly formSortOrderChange = output<number | null>();
  readonly formUrlChange = output<string>();
  readonly formIconChange = output<string>();
  readonly formRequiredPermissionChange = output<string | null>();

  readonly titleChange = output<void>();
  readonly urlBlur = output<void>();
  readonly closeModal = output<void>();
  readonly saveItem = output<void>();

  private readonly targetTypeMemo = optionsMemo<SMTSelectOption<NavigationTargetType>[]>();

  private readonly sectionMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly permissionMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** The text fields give text; null only comes from a number field. */
  asText(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  /** The order field gives a number, or null when it is emptied. */
  asNumber(value: SMTInputValue): number | null {
    return value === null || value === '' ? null : Number(value);
  }

  /** Catalog pairs by name; a stored pair missing from the list stays visible by its key. */
  permissionOptions(): SMTSelectOption<string>[] {
    return this.permissionMemo([this.permissionChoices(), this.formRequiredPermission()], () => {
      const options = this.permissionChoices().map((choice) => ({
        id: choice.permission,
        label: `${choice.formName} — ${choice.actionName}`,
      }));
      const stored = this.formRequiredPermission();
      return stored && !options.some((option) => option.id === stored)
        ? [{ id: stored, label: stored }, ...options]
        : options;
    });
  }

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
