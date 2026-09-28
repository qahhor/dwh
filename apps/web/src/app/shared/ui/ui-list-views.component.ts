import { CdkMenu, CdkMenuGroup, CdkMenuItem, CdkMenuItemRadio, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { ListViewState, SavedListView } from '../list-views/list-views';
import { SMTInputComponent } from '../ui-kit/components/forms/input';
import { SMTModalService } from '../ui-kit/components/modal';
import { SMTButtonComponent } from '../ui-kit/components/button';
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
    SMTButtonComponent,
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

    <smt-dialog [open]="saveAsOpen()" [smtTitle]="'ui.views.save_as_title' | t" smtSize="sm" (closed)="closeSaveAs()">
      <ng-template smtDialogContent>
        <form body class="views-form" (submit)="$event.preventDefault(); submitSaveAs()" novalidate>
          <label class="form-label" [for]="nameId">{{ 'ui.views.name' | t }}</label>
          <smt-input
            #nameInput
            smtTestId="views-name"
            [smtFieldId]="nameId"
            [maxLength]="nameMax"
            [value]="name()"
            (valueChange)="name.set($event === null ? '' : '' + $event)"
            [smtInvalid]="!!nameError()"
            [smtDescribedBy]="nameError() ? nameId + '-error' : null"
          />
          @if (nameError(); as error) {
            <span class="views-error" [id]="nameId + '-error'" data-testid="views-name-error">{{ error | t }}</span>
          }
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
        <div footer class="views-footer">
          <button smt-button type="button" smtVariant="secondary" (click)="closeSaveAs()">
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            data-testid="views-name-submit"
            [smtLoading]="state().busy()"
            (click)="submitSaveAs()"
          >
            {{ 'ui.views.save_button' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './ui-list-views.component.css',
})
export class UiListViewsComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);

  readonly state = input.required<ListViewState>();

  private readonly nameInput = viewChild<SMTInputComponent>('nameInput');

  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly makeDefault = signal(false);
  /** An i18n key shown under the name field. */
  readonly nameError = signal<string | null>(null);

  readonly activeName = computed(() => this.state().active()?.name ?? this.i18n.translate('ui.views.standard'));

  private static nextId = 0;

  readonly nameId = `ui-list-views-name-${UiListViewsComponent.nextId++}`;
  readonly nameMax = NAME_MAX;

  openSaveAs(): void {
    this.name.set('');
    this.makeDefault.set(false);
    this.nameError.set(null);
    this.saveAsOpen.set(true);
    setTimeout(() => this.nameInput()?.focus());
  }

  closeSaveAs(): void {
    this.saveAsOpen.set(false);
  }

  submitSaveAs(): void {
    const name = this.name().trim();
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
          if (problem?.detail === 'LIST_VIEW_NAME_TAKEN') this.nameError.set('ui.views.name_taken');
          else if (problem?.detail === 'LIST_VIEW_LIMIT') this.nameError.set('ui.views.limit');
          else this.toast.error(this.i18n.translate('ui.views.save_error'));
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
            this.i18n.translate(problem?.detail === 'STALE_VERSION' ? 'ui.views.stale' : 'ui.views.save_error'),
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
