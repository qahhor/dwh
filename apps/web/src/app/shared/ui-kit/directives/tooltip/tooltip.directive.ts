/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path directives/tooltip/tooltip.directive.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit, to meet WCAG 1.4.13 and 4.1.2 (the kit showed
 * the tooltip on mouse hover only):
 * - it also shows while the host has keyboard focus, and at once;
 * - Escape dismisses it without moving the pointer or focus;
 * - the pointer can move onto the tooltip without it disappearing;
 * - the text describes the host through CDK AriaDescriber, so a screen
 *   reader announces it whether or not the bubble is on screen;
 * - the bubble has role="tooltip";
 * - the position subscription ends with each bubble instead of piling up
 *   until the host is destroyed. */
import {
  booleanAttribute,
  DestroyRef,
  Directive,
  effect,
  ElementRef,
  inject,
  input,
  NgZone,
  OnDestroy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AriaDescriber } from '@angular/cdk/a11y';
import { ConnectedPosition, Overlay, OverlayPositionBuilder, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { debounceTime, filter, fromEvent, takeUntil, tap } from 'rxjs';
import { TTooltipTheme, SMTTooltipInternalComponent } from './tooltip.component';
import { TOOLTIP_POSITIONS } from './tooltip.constants';

export type TTooltipPosition = 'top' | 'bottom' | 'left' | 'right' | 'auto';

/** Grace period for moving the pointer from the host onto the bubble. */
const HIDE_DELAY_MS = 120;

@Directive({
  selector: '[smtTooltip]',
  standalone: true,
})
export class SMTTooltipDirective implements OnDestroy {
  private elementRef = inject<ElementRef<HTMLElement>>(ElementRef);

  private overlay = inject(Overlay);

  private overlayPositionBuilder = inject(OverlayPositionBuilder);

  private destroyRef = inject(DestroyRef);

  private ngZone = inject(NgZone);

  private ariaDescriber = inject(AriaDescriber);

  text = input('', { alias: 'smtTooltip' });

  supportingText = input('', { alias: 'smtTooltipSupportingText' });

  theme = input<TTooltipTheme>('dark', { alias: 'smtTooltipTheme' });

  position = input<TTooltipPosition>('auto', { alias: 'smtTooltipPosition' });

  disabled = input(false, { alias: 'smtTooltipDisabled', transform: booleanAttribute });

  private overlayRef?: OverlayRef;

  private isMouseOnElement = false;

  private isMouseOnTooltip = false;

  private hasFocus = false;

  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  private describedMessage = '';

  constructor() {
    const host = this.elementRef.nativeElement;

    effect(() => this.updateDescription(this.disabled() ? '' : this.message()));

    this.ngZone.runOutsideAngular(() => {
      fromEvent(host, 'mouseover')
        .pipe(
          tap(() => {
            this.isMouseOnElement = true;
            this.cancelHide();
          }),
          debounceTime(300),
          filter(() => this.isMouseOnElement && !this.overlayRef?.hasAttached()),
          filter(() => this.canShow()),
          takeUntilDestroyed(this.destroyRef)
        )
        .subscribe(() => this.ngZone.run(() => this.attachTooltipLayer()));

      fromEvent(host, 'mouseleave')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => {
          this.isMouseOnElement = false;
          this.scheduleHide();
        });

      fromEvent(host, 'focusin')
        .pipe(
          filter(() => this.canShow()),
          takeUntilDestroyed(this.destroyRef)
        )
        .subscribe(() => {
          this.hasFocus = true;
          this.cancelHide();
          if (!this.overlayRef?.hasAttached()) this.ngZone.run(() => this.attachTooltipLayer());
        });

      fromEvent(host, 'focusout')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => {
          this.hasFocus = false;
          this.scheduleHide();
        });
    });
  }

  ngOnDestroy(): void {
    this.cancelHide();
    this.updateDescription('');
    this.overlayRef?.dispose();
  }

  private message(): string {
    return [this.text(), this.supportingText()].filter(Boolean).join('. ');
  }

  private canShow(): boolean {
    return this.text() !== '' && !this.disabled();
  }

  private updateDescription(message: string): void {
    const host = this.elementRef.nativeElement;
    // A tooltip that only reveals text cut off by an ellipsis repeats what a
    // screen reader already reads from the host; describing it would say it twice.
    if (message && message === host.textContent?.trim()) message = '';
    if (message === this.describedMessage) return;
    if (this.describedMessage) this.ariaDescriber.removeDescription(host, this.describedMessage, 'tooltip');
    if (message) this.ariaDescriber.describe(host, message, 'tooltip');
    this.describedMessage = message;
  }

  private scheduleHide(): void {
    this.cancelHide();
    this.hideTimer = setTimeout(() => {
      this.hideTimer = null;
      if (this.isMouseOnElement || this.isMouseOnTooltip || this.hasFocus) return;
      this.ngZone.run(() => this.detachTooltipLayer());
    }, HIDE_DELAY_MS);
  }

  private cancelHide(): void {
    if (this.hideTimer === null) return;
    clearTimeout(this.hideTimer);
    this.hideTimer = null;
  }

  private attachTooltipLayer() {
    this.overlayRef?.dispose();
    this.isMouseOnTooltip = false;

    const positionStrategy = this.buildOverlayPositionStrategy();

    const overlayRef = this.overlay.create({
      positionStrategy,
      scrollStrategy: this.overlay.scrollStrategies.close({ threshold: 30 }),
    });
    this.overlayRef = overlayRef;

    const tooltipPortal = new ComponentPortal(SMTTooltipInternalComponent, null);
    const componentRef = overlayRef.attach(tooltipPortal);
    componentRef.setInput('text', this.text());
    componentRef.setInput('supportingText', this.supportingText());
    componentRef.setInput('theme', this.theme());
    componentRef.setInput('arrowPosition', 'none');

    const bubble = overlayRef.overlayElement;
    this.ngZone.runOutsideAngular(() => {
      fromEvent(bubble, 'mouseenter')
        .pipe(takeUntil(overlayRef.detachments()))
        .subscribe(() => {
          this.isMouseOnTooltip = true;
          this.cancelHide();
        });
      fromEvent(bubble, 'mouseleave')
        .pipe(takeUntil(overlayRef.detachments()))
        .subscribe(() => {
          this.isMouseOnTooltip = false;
          this.scheduleHide();
        });
    });

    // CDK hands keydown to the topmost overlay only, so Escape closes the
    // tooltip and not a dialog around its host (which also sees it handled).
    overlayRef
      .keydownEvents()
      .pipe(
        filter(event => event.key === 'Escape'),
        takeUntil(overlayRef.detachments())
      )
      .subscribe(event => {
        event.preventDefault();
        event.stopPropagation();
        this.detachTooltipLayer();
      });

    positionStrategy.positionChanges.pipe(takeUntil(overlayRef.detachments())).subscribe(change => {
      const currentArrowPosition = this.getArrowPositionFromConnection(change.connectionPair);
      componentRef.setInput('arrowPosition', currentArrowPosition);
    });
  }

  private detachTooltipLayer() {
    this.cancelHide();
    this.isMouseOnTooltip = false;
    this.overlayRef?.dispose();
    this.overlayRef = undefined;
  }

  private buildOverlayPositionStrategy() {
    const connectedPosition = this.getConnectedPositions();
    return this.overlayPositionBuilder.flexibleConnectedTo(this.elementRef).withPositions(connectedPosition);
  }

  private getConnectedPositions(): ConnectedPosition[] {
    const position = this.position();

    if (position === 'auto') {
      return [
        TOOLTIP_POSITIONS['top'],
        TOOLTIP_POSITIONS['bottom'],
        TOOLTIP_POSITIONS['right'],
        TOOLTIP_POSITIONS['left'],
        TOOLTIP_POSITIONS['top-left'],
        TOOLTIP_POSITIONS['top-right'],
        TOOLTIP_POSITIONS['bottom-left'],
        TOOLTIP_POSITIONS['bottom-right'],
      ];
    }

    switch (position) {
      case 'top':
        return [TOOLTIP_POSITIONS['top'], TOOLTIP_POSITIONS['bottom']];
      case 'left':
        return [TOOLTIP_POSITIONS['left'], TOOLTIP_POSITIONS['right']];
      case 'bottom':
        return [TOOLTIP_POSITIONS['bottom'], TOOLTIP_POSITIONS['top']];
      case 'right':
        return [TOOLTIP_POSITIONS['right'], TOOLTIP_POSITIONS['left']];
      default:
        return [TOOLTIP_POSITIONS['top']];
    }
  }

  private getArrowPositionFromConnection(position: ConnectedPosition): string {
    if (position.originY === 'top' && position.overlayY === 'bottom') {
      if (position.originX === 'center') {
        return 'top-center';
      } else if (position.originX === 'start') {
        return 'top-left';
      } else if (position.originX === 'end') {
        return 'top-right';
      }
    } else if (position.originY === 'bottom' && position.overlayY === 'top') {
      if (position.originX === 'center') {
        return 'bottom-center';
      } else if (position.originX === 'start') {
        return 'bottom-left';
      } else if (position.originX === 'end') {
        return 'bottom-right';
      }
    } else if (position.originX === 'start' && position.overlayX === 'end') {
      return 'left';
    } else if (position.originX === 'end' && position.overlayX === 'start') {
      return 'right';
    }

    return 'none';
  }
}
