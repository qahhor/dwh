import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';

import { CustomNavigationItem, NavigationPermissionChoice, NavigationTargetType } from '@core/models/navigation.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { NavigationItemForm } from '../navigation-item-form';

/**
 * The menu item dialog. The screen owns the form (its model, rules and saving); this draws it by the forms standard:
 * smt-control labels, "*" on the required fields, errors on blur and on save, server errors under their fields,
 * Enter saves, and closing only asks the screen, which asks again when something changed.
 */
@Component({
  selector: 'app-navigation-settings-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormField,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
    TranslatePipe,
  ],
  template: `
    @if (isModalOpen()) {
      <smt-dialog
        [open]="isModalOpen()"
        [smtTitle]="editingItem() ? ('nav.settings.edit_modal_title' | t) : ('nav.settings.create_modal_title' | t)"
        (closed)="closeModal.emit()"
      >
        <ng-template smtDialogContent>
          <form
            id="nav-item-form"
            class="modal-form"
            uiFocusFirstInvalid
            novalidate
            (submit)="$event.preventDefault(); saveItem.emit()"
          >
            <div class="form-row">
              <smt-control
                class="form-group flex-2"
                [smtLabel]="'nav.settings.field_title' | t"
                [smtError]="serverErrors()['title'] ?? ''"
              >
                <!-- (edited) reports every keystroke: each one derives the code from the title again. -->
                <smt-input
                  smtFieldId="nav-title"
                  smtFocusInitial
                  [formField]="itemForm().title"
                  (edited)="titleChange.emit(asText($event))"
                  [placeholder]="'nav.settings.title_placeholder' | t"
                />
              </smt-control>
              <smt-control
                class="form-group flex-1"
                [smtLabel]="'nav.settings.field_code' | t"
                [smtError]="serverErrors()['code'] ?? ''"
              >
                <smt-input smtFieldId="nav-code" [formField]="itemForm().code" placeholder="superset-sales" />
              </smt-control>
            </div>

            <div class="form-row">
              <smt-control class="form-group flex-1" [smtLabel]="'nav.settings.field_type' | t">
                <smt-select
                  smtTriggerId="nav-type"
                  [options]="targetTypeOptions()"
                  [allowClear]="false"
                  [formField]="itemForm().targetType"
                />
              </smt-control>
              <smt-control class="form-group flex-1" [smtLabel]="'nav.settings.field_section' | t">
                <smt-select
                  smtTriggerId="nav-section"
                  [options]="sectionOptions()"
                  [allowClear]="false"
                  [formField]="itemForm().sectionId"
                />
              </smt-control>
              <smt-control
                class="form-group flex-1"
                [smtLabel]="'nav.settings.field_order' | t"
                [smtError]="serverErrors()['sortOrder'] ?? ''"
              >
                <smt-input smtFieldId="nav-order" type="number" [formField]="itemForm().sortOrder" />
              </smt-control>
            </div>

            @if (itemForm().targetType().value() === 'EMBEDDED_IFRAME') {
              <div class="type-hint-box">
                <span class="material-symbols-outlined hint-icon" aria-hidden="true">info</span>
                <span>{{ 'nav.settings.iframe_type_hint' | t }}</span>
              </div>
            }

            <smt-control
              class="form-group"
              [smtLabel]="'nav.settings.field_url' | t"
              [smtHint]="'nav.settings.url_hint' | t"
              [smtError]="serverErrors()['url'] ?? ''"
            >
              <smt-input
                smtFieldId="nav-url"
                [formField]="itemForm().url"
                (touch)="urlBlur.emit()"
                placeholder="https://bi.company.uz/superset/dashboard/123/"
              />
            </smt-control>

            <smt-control
              class="form-group"
              [smtLabel]="'nav.settings.field_permission' | t"
              [smtHint]="'nav.settings.permission_hint' | t"
              [smtError]="serverErrors()['requiredPermission'] ?? ''"
            >
              <smt-select
                smtTriggerId="nav-permission"
                [options]="permissionOptions()"
                [allowClear]="true"
                [placeholder]="'nav.settings.permission_everyone' | t"
                [formField]="itemForm().requiredPermission"
              />
            </smt-control>

            <smt-control class="form-group" [smtLabel]="'nav.settings.field_icon' | t">
              <div class="icon-selector-row">
                <smt-input
                  smtFieldId="nav-icon"
                  class="icon-input"
                  [formField]="itemForm().icon"
                  placeholder="analytics"
                />
                <span class="material-symbols-outlined icon-preview" aria-hidden="true">{{
                  itemForm().icon().value() || 'bar_chart'
                }}</span>
              </div>
            </smt-control>
            <div class="icon-quick-chips" role="group" [attr.aria-label]="'nav.settings.field_icon' | t">
              @for (ic of popularIcons(); track ic) {
                <button
                  type="button"
                  class="chip-btn"
                  [class.active]="itemForm().icon().value() === ic"
                  [attr.aria-label]="ic"
                  [attr.aria-pressed]="itemForm().icon().value() === ic"
                  (click)="itemForm().icon().value.set(ic)"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">{{ ic }}</span>
                </button>
              }
            </div>
          </form>

          <ui-form-actions
            footer
            form="nav-item-form"
            data-testid="nav-item-actions"
            [submitLabel]="(editingItem() ? 'common.save' : 'common.create') | t"
            [submitting]="isSubmitting()"
            (cancelled)="closeModal.emit()"
          />
        </ng-template>
      </smt-dialog>
    }
  `,
  styleUrl: './navigation-settings-modal.component.css',
})
export class NavigationSettingsModalComponent {
  private readonly i18n = inject(I18nService);

  /** The item form the screen owns. */
  readonly itemForm = input.required<FieldTree<NavigationItemForm>>();
  readonly editingItem = input<CustomNavigationItem | null>(null);
  readonly isSubmitting = input(false);
  readonly popularIcons = input<string[]>([]);
  readonly isModalOpen = input(false);
  readonly permissionChoices = input<NavigationPermissionChoice[]>([]);
  /** The server's refusal by form field, already in words. */
  readonly serverErrors = input<Readonly<Record<string, string>>>({});

  /** The title as typed, on every keystroke. */
  readonly titleChange = output<string>();
  readonly urlBlur = output<void>();
  /** Escape, the backdrop, the cross or "Cancel": the screen decides. */
  readonly closeModal = output<void>();
  readonly saveItem = output<void>();

  private readonly targetTypeMemo = optionsMemo<SMTSelectOption<NavigationTargetType>[]>();

  private readonly sectionMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly permissionMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** The text fields give text; null only comes from a number field. */
  asText(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  /** Catalog pairs by name; a stored pair missing from the list stays visible by its key. */
  permissionOptions(): SMTSelectOption<string>[] {
    const stored = this.itemForm().requiredPermission().value();
    return this.permissionMemo([this.permissionChoices(), stored], () => {
      const options = this.permissionChoices().map((choice) => ({
        id: choice.permission,
        label: `${choice.formName} — ${choice.actionName}`,
      }));
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
