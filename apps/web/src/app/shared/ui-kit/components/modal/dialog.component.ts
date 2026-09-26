/* Our code: a declarative dialog on top of the kit's modal (SMTModalService
 * and smt-modal's look). See ADR-0015 rule 2.
 *
 * The kit opens dialogs from code. Screens here describe theirs in the
 * template and bind an "open" flag, so this component keeps that shape and
 * hands the work to the kit: when `open` turns true it opens a CDK dialog
 * with smt-modal's header (the title names the dialog, a named close button)
 * and the screen's content; when it turns false the dialog closes. The CDK
 * gives the overlay, the focus trap that returns focus to the opener, the
 * scroll lock and the stacking with other overlays (a select's list, a
 * confirmation opened from the dialog).
 *
 * The content sits in an <ng-template>, so it is created when the dialog
 * opens and destroyed when it closes, like the old `*ngIf="isOpen"`.
 * Elements marked `footer` (or `modal-footer`, `slot="footer"`) form the
 * footer row at the bottom. Escape, a backdrop click and the close button
 * only ask: they emit `closed`, and the dialog closes when the screen sets
 * `open` to false — a screen with unsaved changes may keep it open.
 *
 * <smt-dialog [open]="editing()" [smtTitle]="'…' | t" smtSize="lg" (closed)="cancel()">
 *   <ng-template smtDialogContent>
 *     <form>…</form>
 *     <div footer><button smt-button …>Save</button></div>
 *   </ng-template>
 * </smt-dialog> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  contentChild,
  DestroyRef,
  Directive,
  effect,
  inject,
  input,
  output,
  TemplateRef,
  untracked,
  viewChild,
  ViewContainerRef,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import type { DialogRef } from '@angular/cdk/dialog';
import { SMTI18nService } from '../../i18n';
import { SMTModalService } from './modal.service';

export type SMTDialogSize = 'sm' | 'md' | 'lg' | 'xl';

let nextDialogId = 0;

const WIDTH: Record<SMTDialogSize, string> = { sm: '400px', md: '580px', lg: '800px', xl: '1100px' };

/** Marks the template that holds a dialog's content. */
@Directive({ selector: 'ng-template[smtDialogContent]', standalone: true })
export class SMTDialogContentDirective {
  readonly template = inject(TemplateRef);
}

@Component({
  selector: 'smt-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet],
  template: `
    <ng-template #shell>
      <div class="smt-modal smt-dialog">
        @if (title() || dismissible()) {
          <div class="smt-modal__header">
            @if (title()) {
              <h2 class="smt-modal__title" [id]="titleId">{{ title() }}</h2>
            }
            @if (dismissible()) {
              <button type="button" class="smt-modal__close" [attr.aria-label]="i18n.messages().common.close" (click)="dismiss()">
                <span class="material-symbols-outlined" aria-hidden="true">close</span>
              </button>
            }
          </div>
        }
        <div class="smt-modal__body smt-dialog__body">
          @if (content(); as body) {
            <ng-container [ngTemplateOutlet]="body.template" />
          }
        </div>
      </div>
    </ng-template>
  `,
})
export class SMTDialogComponent {
  readonly i18n = inject(SMTI18nService);

  private readonly modal = inject(SMTModalService);

  private readonly viewContainer = inject(ViewContainerRef);

  readonly open = input(false, { transform: booleanAttribute });

  readonly title = input('', { alias: 'smtTitle' });

  readonly size = input<SMTDialogSize>('md', { alias: 'smtSize' });

  /** false: no close button, Escape and the backdrop do nothing (the screen closes it). */
  readonly dismissible = input(true, { transform: booleanAttribute });

  /** The dialog's name where it has no title. */
  readonly ariaLabel = input('', { alias: 'smtAriaLabel' });

  /** The person asked to close (Escape, backdrop, close button). */
  readonly closed = output<void>();

  private readonly shell = viewChild.required<TemplateRef<unknown>>('shell');

  readonly content = contentChild(SMTDialogContentDirective);

  readonly titleId = `smt-dialog-title-${nextDialogId++}`;

  private ref: DialogRef<unknown, unknown> | null = null;

  constructor() {
    effect(() => {
      const open = this.open();
      untracked(() => (open ? this.show() : this.hide()));
    });
    inject(DestroyRef).onDestroy(() => this.hide());
  }

  /** The person asks to close; the screen decides, through `open`. */
  dismiss(): void {
    if (this.dismissible()) this.closed.emit();
  }

  private show(): void {
    if (this.ref) return;
    this.ref = this.modal.open(this.shell(), {
      width: WIDTH[this.size()],
      minWidth: 'min(320px, calc(100vw - 32px))',
      viewContainerRef: this.viewContainer,
      ariaLabelledBy: this.title() ? this.titleId : undefined,
      ariaLabel: this.title() ? undefined : this.ariaLabel() || undefined,
      // Escape and the backdrop only ask; the dialog closes when the screen sets `open` to false.
      canDismiss: () => {
        this.dismiss();
        return false;
      },
    });
  }

  private hide(): void {
    const ref = this.ref;
    this.ref = null;
    ref?.close();
  }
}
