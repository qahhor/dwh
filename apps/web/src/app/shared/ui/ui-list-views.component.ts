import { CdkMenu, CdkMenuGroup, CdkMenuItem, CdkMenuItemRadio, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, signal, viewChild } from '@angular/core';
import { ProblemDetail } from '../../core/models/common.models';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { ToastService } from '../../core/services/toast.service';
import { ListViewState, SavedListView } from '../list-views/list-views';
import { SMTModalService } from '../ui-kit/components/modal';
import { UiButtonComponent } from './ui-button.component';
import { UiModalComponent } from './ui-modal.component';

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
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkMenuTrigger, CdkMenu, CdkMenuGroup, CdkMenuItem, CdkMenuItemRadio, TranslatePipe, UiModalComponent, UiButtonComponent],
  template: `
    <button type="button" class="views-trigger" data-testid="views-trigger" [cdkMenuTriggerFor]="menu" [disabled]="state().busy()">
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
          <button type="button" class="views-item" cdkMenuItemRadio data-testid="views-standard"
            [cdkMenuItemChecked]="state().activeId() === null" (cdkMenuItemTriggered)="state().apply(null)">
            <span class="views-check material-symbols-outlined" aria-hidden="true">{{ state().activeId() === null ? 'check' : '' }}</span>
            <span>{{ 'ui.views.standard' | t }}</span>
          </button>
          @for (view of state().views(); track view.id) {
            <button type="button" class="views-item" cdkMenuItemRadio data-testid="views-item"
              [cdkMenuItemChecked]="state().activeId() === view.id" (cdkMenuItemTriggered)="state().apply(view)">
              <span class="views-check material-symbols-outlined" aria-hidden="true">{{ state().activeId() === view.id ? 'check' : '' }}</span>
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
            <button type="button" class="views-item" cdkMenuItem data-testid="views-save" (cdkMenuItemTriggered)="saveActive()">
              {{ 'ui.views.save' | t }}
            </button>
          }
        }
        <button type="button" class="views-item" cdkMenuItem data-testid="views-save-as" (cdkMenuItemTriggered)="openSaveAs()">
          {{ 'ui.views.save_as' | t }}
        </button>
        @if (state().active(); as active) {
          <button type="button" class="views-item" cdkMenuItem data-testid="views-default" (cdkMenuItemTriggered)="toggleDefault(active)">
            {{ (active.isDefault ? 'ui.views.unset_default' : 'ui.views.set_default') | t }}
          </button>
          <button type="button" class="views-item views-danger" cdkMenuItem data-testid="views-delete" (cdkMenuItemTriggered)="remove(active)">
            {{ 'ui.views.delete' | t }}
          </button>
        }
      </div>
    </ng-template>

    <ui-modal [isOpen]="saveAsOpen()" [title]="'ui.views.save_as_title' | t" size="sm" (close)="closeSaveAs()">
      <form body class="views-form" (submit)="$event.preventDefault(); submitSaveAs()" novalidate>
        <label class="form-label" [for]="nameId">{{ 'ui.views.name' | t }}</label>
        <input #nameInput class="form-input" type="text" data-testid="views-name" [id]="nameId" [maxLength]="nameMax"
          [value]="name()" (input)="name.set($any($event.target).value)"
          [attr.aria-invalid]="nameError() ? 'true' : null" [attr.aria-describedby]="nameError() ? nameId + '-error' : null" />
        @if (nameError(); as error) {
          <span class="views-error" [id]="nameId + '-error'" data-testid="views-name-error">{{ error | t }}</span>
        }
        <label class="views-default-choice">
          <input type="checkbox" data-testid="views-name-default" [checked]="makeDefault()" (change)="makeDefault.set($any($event.target).checked)" />
          {{ 'ui.views.open_by_default' | t }}
        </label>
      </form>
      <div footer class="views-footer">
        <ui-button variant="secondary" (onClick)="closeSaveAs()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" data-testid="views-name-submit" [loading]="state().busy()" (onClick)="submitSaveAs()">
          {{ 'ui.views.save_button' | t }}
        </ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    :host { display: inline-flex; }
    .views-trigger {
      display: inline-flex; align-items: center; gap: 6px; max-width: 280px; min-height: var(--control-height);
      padding: 0 10px; border: 1px solid var(--border-color); border-radius: var(--radius-md);
      background: var(--bg-surface); color: var(--text-main); font-size: 13px; cursor: pointer;
    }
    .views-trigger:hover { background: var(--bg-hover); color: var(--text-main); }
    .views-trigger:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
    .views-trigger .material-symbols-outlined { font-size: 18px; }
    .views-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .views-changed { color: var(--text-muted); font-size: 12px; }
    .views-menu {
      display: flex; flex-direction: column; min-width: 240px; max-width: min(360px, calc(100vw - 32px)); padding: 6px;
      border: 1px solid var(--border-color); border-radius: var(--radius-lg); background: var(--bg-surface);
      color: var(--text-main); box-shadow: var(--shadow-overlay);
    }
    .views-item {
      display: flex; align-items: center; gap: 8px; min-height: 34px; padding: 0 10px; border: none;
      border-radius: var(--radius-sm); background: none; color: var(--text-main); font-size: 13px; text-align: start; cursor: pointer;
    }
    .views-item:hover, .views-item:focus { background: var(--bg-hover); color: var(--text-main); outline: none; }
    .views-item:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
    .views-check { width: 18px; font-size: 18px; }
    .views-item-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .views-badge { color: var(--text-muted); font-size: 12px; }
    .views-danger { color: var(--danger-text); }
    .views-danger:hover, .views-danger:focus { color: var(--danger-text); }
    .views-separator { height: 1px; margin: 6px 0; background: var(--border-color); }
    .views-form { display: flex; flex-direction: column; gap: 8px; }
    .views-error { color: var(--danger-text); font-size: 12px; }
    .views-default-choice { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; }
    .views-footer { display: flex; justify-content: flex-end; gap: 8px; }
  `],
})
export class UiListViewsComponent {
  private static nextId = 0;

  readonly state = input.required<ListViewState>();

  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly nameId = `ui-list-views-name-${UiListViewsComponent.nextId++}`;
  readonly nameMax = NAME_MAX;
  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly makeDefault = signal(false);
  /** An i18n key shown under the name field. */
  readonly nameError = signal<string | null>(null);

  readonly activeName = computed(() => this.state().active()?.name ?? this.i18n.translate('ui.views.standard'));

  openSaveAs(): void {
    this.name.set('');
    this.makeDefault.set(false);
    this.nameError.set(null);
    this.saveAsOpen.set(true);
    setTimeout(() => this.nameInput()?.nativeElement.focus());
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
    this.state().saveAs(name, this.makeDefault()).subscribe({
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
    this.state().saveActive().subscribe({
      next: () => this.toast.success(this.i18n.translate('ui.views.saved')),
      error: (problem: ProblemDetail) => this.toast.error(this.i18n.translate(
        problem?.detail === 'STALE_VERSION' ? 'ui.views.stale' : 'ui.views.save_error')),
    });
  }

  toggleDefault(view: SavedListView): void {
    this.state().setDefault(view, !view.isDefault).subscribe({
      error: () => this.toast.error(this.i18n.translate('ui.views.save_error')),
    });
  }

  remove(view: SavedListView): void {
    this.modal.confirm({
      message: this.i18n.translate('ui.views.delete_confirm', { name: view.name }),
      destructive: true,
    }).subscribe(confirmed => {
      if (!confirmed) return;
      this.state().remove(view).subscribe({
        next: () => this.toast.success(this.i18n.translate('ui.views.deleted')),
        error: () => this.toast.error(this.i18n.translate('ui.views.save_error')),
      });
    });
  }
}
