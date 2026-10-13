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
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { messageForError } from '@shared/ui-kit/components/forms/control/control-messages';

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
    UiFormErrorSummaryComponent,
    UiFocusFirstInvalidDirective,
    TranslatePipe,
  ],
  templateUrl: './navigation-settings-modal.component.html',
  styleUrl: './navigation-settings-modal.component.css',
})
export class NavigationSettingsModalComponent {
  private readonly i18n = inject(I18nService);
  private readonly kitI18n = inject(SMTI18nService);

  /** The item form the screen owns. */
  readonly itemForm = input.required<FieldTree<NavigationItemForm>>();
  readonly editingItem = input<CustomNavigationItem | null>(null);
  readonly isSubmitting = input(false);
  readonly popularIcons = input<string[]>([]);
  readonly isModalOpen = input(false);
  readonly permissionChoices = input<NavigationPermissionChoice[]>([]);
  /** The server's refusal by form field, already in words. */
  readonly serverErrors = input<Readonly<Record<string, string>>>({});
  readonly otherErrors = input<readonly string[]>([]);
  readonly fieldEdited = output<string>();

  /** The title as typed, on every keystroke. */
  readonly titleChange = output<string>();
  readonly urlBlur = output<void>();
  /** Escape, the backdrop, the cross or "Cancel": the screen decides. */
  readonly closeModal = output<void>();
  readonly saveItem = output<void>();

  private readonly targetTypeMemo = optionsMemo<SMTSelectOption<NavigationTargetType>[]>();

  private readonly sectionMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly permissionMemo = optionsMemo<SMTSelectOption<string>[]>();

  summary(): UiFormErrorItem[] {
    const fields = [
      ['title', 'nav-title', 'nav.settings.field_title'],
      ['code', 'nav-code', 'nav.settings.field_code'],
      ['sortOrder', 'nav-order', 'nav.settings.field_order'],
      ['url', 'nav-url', 'nav.settings.field_url'],
      ['requiredPermission', 'nav-permission', 'nav.settings.field_permission'],
    ] as const;
    const errors: UiFormErrorItem[] = fields.flatMap(([key, fieldId, label]) => {
      const field = this.itemForm()[key]();
      const message =
        this.serverErrors()[key] ??
        (field.touched() ? messageForError(field.errors()[0], this.kitI18n.messages().control) : '');
      return message ? [{ fieldId, label: this.i18n.translate(label), message }] : [];
    });
    return [...errors, ...this.otherErrors().map((message) => ({ message }))];
  }

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
