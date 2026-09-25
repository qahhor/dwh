/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/drawer/drawer.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: the panel is a modal `dialog` named by its
 * title (or `ariaLabel`); the close button sits in a header row with an
 * accessible name instead of floating over the content; motion is skipped
 * when the user prefers reduced motion or the browser cannot animate;
 * styles come from our tokens (`drawer.scss`), so both themes work. */
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  model,
  signal,
  untracked,
  viewChild,
  ViewContainerRef,
  ViewEncapsulation,
} from '@angular/core';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { SMTI18nService } from '../../i18n';
import { DRAWER_ANIMATION_MS, drawerShouldAnimate } from './drawer-motion';
import { SMT_DRAWER_CONFIG, SMT_DRAWER_REF, SMTDrawerConfig } from './drawer.types';

const DRAWER_ENTER_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
const DRAWER_LEAVE_EASING = 'ease-in';
const PANEL_HIDDEN = 'translate3d(100%, 0, 0)';
const PANEL_VISIBLE = 'translate3d(0, 0, 0)';

let nextDrawerId = 0;

@Component({
  selector: 'smt-drawer',
  standalone: true,
  templateUrl: './drawer.component.html',
  styleUrl: './drawer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [CdkTrapFocus],
  host: {
    class: 'smt-drawer-host',
  },
})
export class SMTDrawerComponent {
  readonly i18n = inject(SMTI18nService);
  private readonly drawerRef = inject(SMT_DRAWER_REF);

  private readonly config = inject<SMTDrawerConfig>(SMT_DRAWER_CONFIG);

  className = input<string>('', { alias: 'smtContainerClass' });

  isClosing = model(false);

  contentContainer = viewChild.required('contentContainer', { read: ViewContainerRef });

  private readonly panelRef = viewChild<ElementRef<HTMLElement>>('panel');

  readonly trapAutoCapture = signal(false);

  /** Panel width (inline style), from drawer config or default */
  readonly panelWidth = computed(() => this.config.width ?? '90dvw');

  readonly titleId = `smt-drawer-title-${nextDrawerId++}`;

  readonly title = this.config.title ?? '';

  readonly ariaLabel = this.config.ariaLabel ?? null;

  constructor() {
    const destroyRef = inject(DestroyRef);

    afterNextRender(() => {
      const el = this.panelRef()?.nativeElement;
      if (!el || !drawerShouldAnimate(el)) {
        this.trapAutoCapture.set(true);
        return;
      }

      const animation = el.animate([{ transform: PANEL_HIDDEN }, { transform: PANEL_VISIBLE }], {
        duration: DRAWER_ANIMATION_MS,
        easing: DRAWER_ENTER_EASING,
        fill: 'forwards',
      });
      void animation.finished.then(() => this.trapAutoCapture.set(true)).catch(() => undefined);
      destroyRef.onDestroy(() => animation.cancel());
    });

    effect(() => {
      if (!this.isClosing()) return;
      untracked(() => this.playLeave());
    });
  }

  onHitboxClick(): void {
    if (this.config.closeOnBackdropClick !== true) {
      return;
    }
    this.close();
  }

  close(): void {
    if (this.config.onCloseRequest) {
      this.config.onCloseRequest();
      return;
    }
    this.drawerRef.close();
  }

  private playLeave(): void {
    const el = this.panelRef()?.nativeElement;
    if (!el || !drawerShouldAnimate(el)) return;
    for (const animation of el.getAnimations()) {
      animation.cancel();
    }
    el.animate([{ transform: PANEL_VISIBLE }, { transform: PANEL_HIDDEN }], {
      duration: DRAWER_ANIMATION_MS,
      easing: DRAWER_LEAVE_EASING,
      fill: 'forwards',
    });
  }
}
