/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/modal.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: the header title names the dialog (it is added
 * to the CDK container's aria-labelledby, as MatDialogTitle does); the close
 * button is a plain icon button with a Material Symbols ligature instead of
 * the kit button and SVG icon; the `SMT_MODAL_ACTIONS` provider was dropped
 * because nothing here injects it. */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  ViewEncapsulation,
} from '@angular/core';
import { NgClass, NgTemplateOutlet } from '@angular/common';
import { CdkDialogContainer, DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import type { SMTModalData } from './types/modal.types';
import { SMTI18nService } from '@shared/ui-kit/i18n';

let nextTitleId = 0;

@Component({
  selector: 'smt-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [NgClass, NgTemplateOutlet],
  templateUrl: './modal.component.html',
  // modal.scss is a global stylesheet (angular.json): a component's styles load only when it is first created,
  // and smt-dialog opens its panel without an smt-modal, so the first dialog of a page was left unstyled.
  host: {
    class: 'smt-modal',
  },
})
export class SMTModalComponent {
  readonly i18n = inject(SMTI18nService);

  private readonly dialogRef = inject(DialogRef);

  private readonly data = inject<SMTModalData | null>(DIALOG_DATA, { optional: true });

  readonly title = input<string>('', { alias: 'smtTitle' });

  readonly showCloseButton = input<boolean>(true, { alias: 'smtShowCloseButton' });

  readonly bodyClass = input<string>('', { alias: 'smtBodyClass' });

  /** Template from service data - used when opened programmatically */
  readonly contentTemplate = computed(() => this.data?.content ?? null);

  readonly displayTitle = computed(() => this.title() || this.data?.title || '');

  readonly displayShowCloseButton = computed(() => {
    if (this.data?.showCloseButton !== undefined) {
      return this.data.showCloseButton;
    }
    return this.showCloseButton();
  });

  readonly titleId = `smt-modal-title-${nextTitleId++}`;

  /** Context passed to the content template - provides close and save callbacks */
  readonly templateContext = {
    close: () => this.close(),
    save: () => {
      this.data?.onSave?.();
      this.dialogRef.close(true);
    },
  };

  constructor() {
    const container = this.dialogRef.containerInstance as CdkDialogContainer | undefined;
    let labelled = false;
    effect(() => {
      const hasTitle = !!this.displayTitle();
      if (!container || hasTitle === labelled) return;
      if (hasTitle) {
        container._addAriaLabelledBy(this.titleId);
      } else {
        container._removeAriaLabelledBy(this.titleId);
      }
      labelled = hasTitle;
    });
    inject(DestroyRef).onDestroy(() => {
      if (labelled) container?._removeAriaLabelledBy(this.titleId);
    });
  }

  /** Public close - used by header X button */
  close(): void {
    this.data?.onClose?.();
    this.dialogRef.close();
  }
}
