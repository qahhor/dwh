import { CdkMenu, CdkMenuGroup, CdkMenuItem, CdkMenuItemRadio, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, Injector, input, signal } from '@angular/core';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { ListViewState, SavedListView } from '../list-views/list-views';
import { SMTInputComponent } from '../ui-kit/components/forms/input';
import { SMTModalService } from '../ui-kit/components/modal';
import { SMTControlComponent } from '../ui-kit/components/forms/control';
import { discardChangesQuestion } from './discard-changes';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from './focus-first-invalid';
import { problemFieldErrors } from './problem-fields';
import { UiFormActionsComponent } from './ui-form-actions.component';
import { SMTDialogComponent, SMTDialogContentDirective } from '../ui-kit/components/modal';
import { SMTCheckboxComponent } from '../ui-kit/components/forms/checkbox';

const NAME_MAX = 80;

/**
 * The views menu of a list: switch between the standard view and saved ones,
 * save what is on screen as a new view or into the active one, choose the view
 * the list opens with, and remove a view. It is a menu button (WAI-ARIA APG
 * menu button, via the CDK menu): the views are radio items, so the current
 * one is announced as checked; the button says when the view on screen has
 * unsaved changes.
 */
@Component({
  selector: 'ui-list-views',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTInputComponent,
    CdkMenuTrigger,
    CdkMenu,
    CdkMenuGroup,
    CdkMenuItem,
    CdkMenuItemRadio,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTControlComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
  ],
  template: `
    <button
      type="button"
      class="views-trigger"
      data-testid="views-trigger"
      [cdkMenuTriggerFor]="menu"
      [disabled]="state().busy()"
    >
      <span class="material-symbols-outlined" aria-hidden="true">bookmarks</span>
      <span class="views-name">{{ activeName() }}</span>
      @if (state().changed()) {
        <span class="views-changed">{{ 'ui.views.changed' | t }}</span>
      }
      <span class="material-symbols-outlined" aria-hidden="true">expand_more</span>
    </button>

    <ng-template #menu>
      <div class="views-menu" cdkMenu role="menu" [attr.aria-label]="'ui.views.menu' | t">
        <div cdkMenuGroup>
          <button
            type="button"
            class="views-item"
            cdkMenuItemRadio
            data-testid="views-standard"
            [cdkMenuItemChecked]="state().activeId() === null"
            (cdkMenuItemTriggered)="state().apply(null)"
          >
            <span class="views-check material-symbols-outlined" aria-hidden="true">{{
              state().activeId() === null ? 'check' : ''
            }}</span>
            <span>{{ 'ui.views.standard' | t }}</span>
          </button>
          @for (view of state().views(); track view.id) {
            <button
              type="button"
              class="views-item"
              cdkMenuItemRadio
              data-testid="views-item"
              [cdkMenuItemChecked]="state().activeId() === view.id"
              (cdkMenuItemTriggered)="state().apply(view)"
            >
              <span class="views-check material-symbols-outlined" aria-hidden="true">{{
                state().activeId() === view.id ? 'check' : ''
              }}</span>
              <span class="views-item-name">{{ view.name }}</span>
              @if (view.isDefault) {
                <span class="views-badge">{{ 'ui.views.default_badge' | t }}</span>
              }
            </button>
          }
        </div>
        <div class="views-separator" role="separator"></div>
        @if (state().active(); as active) {
          @if (state().changed()) {
            <button
              type="button"
              class="views-item"
              cdkMenuItem
              data-testid="views-save"
              (cdkMenuItemTriggered)="saveActive()"
            >
              {{ 'ui.views.save' | t }}
            </button>
          }
        }
        <button
          type="button"
          class="views-item"
          cdkMenuItem
          data-testid="views-save-as"
          (cdkMenuItemTriggered)="openSaveAs()"
        >
          {{ 'ui.views.save_as' | t }}
        </button>
        @if (state().active(); as active) {
          <button
            type="button"
            class="views-item"
            cdkMenuItem
            data-testid="views-default"
            (cdkMenuItemTriggered)="toggleDefault(active)"
          >
            {{ (active.isDefault ? 'ui.views.unset_default' : 'ui.views.set_default') | t }}
          </button>
          <button
            type="button"
            class="views-item views-danger"
            cdkMenuItem
            data-testid="views-delete"
            (cdkMenuItemTriggered)="remove(active)"
          >
            {{ 'ui.views.delete' | t }}
          </button>
        }
      </div>
    </ng-template>

    <smt-dialog
      [open]="saveAsOpen()"
      [smtTitle]="'ui.views.save_as_title' | t"
      smtSize="sm"
      [dismissible]="!state().busy()"
      (closed)="closeSaveAs()"
    >
      <ng-template smtDialogContent>
        <form
          class="views-form"
          [id]="nameId + '-form'"
          uiFocusFirstInvalid
          (submit)="$event.preventDefault(); submitSaveAs()"
          novalidate
        >
          <smt-control [smtLabel]="'ui.views.name' | t" [smtError]="nameErrorText()" [required]="true">
            <smt-input
              smtTestId="views-name"
              smtFocusInitial
              [smtFieldId]="nameId"
              [maxLength]="nameMax"
              [value]="name()"
              (valueChange)="name.set($event === null ? '' : '' + $event)"
            />
          </smt-control>
          <div
            smt-checkbox
            class="views-default-choice"
            data-testid="views-name-default"
            [checked]="makeDefault()"
            (checkedChange)="makeDefault.set($event)"
          >
            {{ 'ui.views.open_by_default' | t }}
          </div>
        </form>
        <ui-form-actions
          footer
          [form]="nameId + '-form'"
          data-testid="views-save-as-actions"
          [submitting]="state().busy()"
          (cancelled)="closeSaveAs()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './ui-list-views.component.css',
})
export class UiListViewsComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);

  private readonly injector = inject(Injector);

  readonly state = input.required<ListViewState>();

  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly makeDefault = signal(false);
  /** An i18n key shown under the name field. */
  readonly nameError = signal<string | null>(null);
  /** The server's own words on the name, when it names the field without a known key. */
  readonly nameProblem = signal('');

  /** The error under the name: ours by key, or the server's words. */
  readonly nameErrorText = computed(() => {
    this.i18n.currentLang();
    const key = this.nameError();
    return key ? this.i18n.translate(key) : this.nameProblem();
  });

  readonly activeName = computed(() => this.state().active()?.name ?? this.i18n.translate('ui.views.standard'));

  private readonly askDiscard = discardChangesQuestion();

  private static nextId = 0;

  readonly nameId = `ui-list-views-name-${UiListViewsComponent.nextId++}`;
  readonly nameMax = NAME_MAX;

  openSaveAs(): void {
    this.name.set('');
    this.makeDefault.set(false);
    this.nameError.set(null);
    this.nameProblem.set('');
    this.saveAsOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a typed name is dropped (forms standard, 8). */
  closeSaveAs(): void {
    if (this.state().busy()) return;
    this.askDiscard(!!this.name().trim() || this.makeDefault()).subscribe((discard) => {
      if (discard) this.saveAsOpen.set(false);
    });
  }

  /** Enter and "Save" land here; one request while a save runs; an empty name says so and takes the focus. */
  submitSaveAs(): void {
    if (this.state().busy()) return;
    const name = this.name().trim();
    this.nameProblem.set('');
    if (name.length === 0) {
      this.nameError.set('ui.views.name_required');
      return;
    }
    this.nameError.set(null);
    this.state()
      .saveAs(name, this.makeDefault())
      .subscribe({
        next: () => {
          this.saveAsOpen.set(false);
          this.toast.success(this.i18n.translate('ui.views.saved'));
        },
        error: (problem: ProblemDetail) => {
          const { fields } = problemFieldErrors(problem, { known: ['name'] });
          if (problem?.messageKey === 'error.md.list_view_name_taken') this.nameError.set('ui.views.name_taken');
          else if (problem?.messageKey === 'error.md.list_view_limit') this.nameError.set('ui.views.limit');
          else if (fields['name']) this.nameProblem.set(fields['name']);
          else {
            this.toast.error(this.i18n.translate('ui.views.save_error'));
            return;
          }
          const form = document.getElementById(`${this.nameId}-form`);
          if (form) focusFirstInvalid(form, this.injector);
        },
      });
  }

  saveActive(): void {
    this.state()
      .saveActive()
      .subscribe({
        next: () => this.toast.success(this.i18n.translate('ui.views.saved')),
        error: (problem: ProblemDetail) =>
          this.toast.error(
            this.i18n.translate(
              problem?.messageKey === 'error.md.list_view_stale' ? 'ui.views.stale' : 'ui.views.save_error',
            ),
          ),
      });
  }

  toggleDefault(view: SavedListView): void {
    this.state()
      .setDefault(view, !view.isDefault)
      .subscribe({
        error: () => this.toast.error(this.i18n.translate('ui.views.save_error')),
      });
  }

  remove(view: SavedListView): void {
    this.modal
      .confirm({
        message: this.i18n.translate('ui.views.delete_confirm', { name: view.name }),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.state()
          .remove(view)
          .subscribe({
            next: () => this.toast.success(this.i18n.translate('ui.views.deleted')),
            error: () => this.toast.error(this.i18n.translate('ui.views.save_error')),
          });
      });
  }
}
