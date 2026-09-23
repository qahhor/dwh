/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path directives/tooltip/tooltip.directive.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { booleanAttribute, DestroyRef, Directive, ElementRef, inject, input, NgZone, OnDestroy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ConnectedPosition, Overlay, OverlayPositionBuilder, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { debounceTime, filter, fromEvent, tap } from 'rxjs';
import { TTooltipTheme, SMTTooltipInternalComponent } from './tooltip.component';
import { TOOLTIP_POSITIONS } from './tooltip.constants';

export type TTooltipPosition = 'top' | 'bottom' | 'left' | 'right' | 'auto';

@Directive({
  selector: '[smtTooltip]',
  standalone: true,
})
export class SMTTooltipDirective implements OnDestroy {
  private elementRef = inject(ElementRef<HTMLElement>);

  private overlay = inject(Overlay);

  private overlayPositionBuilder = inject(OverlayPositionBuilder);

  private destroyRef = inject(DestroyRef);

  private ngZone = inject(NgZone);

  text = input('', { alias: 'smtTooltip' });

  supportingText = input('', { alias: 'smtTooltipSupportingText' });

  theme = input<TTooltipTheme>('dark', { alias: 'smtTooltipTheme' });

  position = input<TTooltipPosition>('auto', { alias: 'smtTooltipPosition' });

  disabled = input(false, { alias: 'smtTooltipDisabled', transform: booleanAttribute });

  private overlayRef?: OverlayRef;

  private isMouseOnElement = false;

  constructor() {
    this.ngZone.runOutsideAngular(() => {
      fromEvent(this.elementRef.nativeElement, 'mouseover')
        .pipe(
          tap(() => (this.isMouseOnElement = true)),
          debounceTime(300),
          filter(() => this.isMouseOnElement),
          filter(() => this.text() !== '' && !this.disabled()),
          takeUntilDestroyed(this.destroyRef)
        )
        .subscribe(() => this.ngZone.run(() => this.attachTooltipLayer()));

      fromEvent(this.elementRef.nativeElement, 'mouseleave')
        .pipe(
          tap(() => (this.isMouseOnElement = false)),
          takeUntilDestroyed(this.destroyRef)
        )
        .subscribe(() => this.ngZone.run(() => this.detachTooltipLayer()));
    });
  }

  ngOnDestroy(): void {
    this.overlayRef?.dispose();
  }

  private attachTooltipLayer() {
    this.overlayRef?.dispose();

    const positionStrategy = this.buildOverlayPositionStrategy();

    this.overlayRef = this.overlay.create({
      positionStrategy,
      scrollStrategy: this.overlay.scrollStrategies.close({ threshold: 30 }),
    });

    const tooltipPortal = new ComponentPortal(SMTTooltipInternalComponent, null);
    const componentRef = this.overlayRef.attach(tooltipPortal);
    componentRef.setInput('text', this.text());
    componentRef.setInput('supportingText', this.supportingText());
    componentRef.setInput('theme', this.theme());
    componentRef.setInput('arrowPosition', 'none');

    positionStrategy.positionChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(change => {
      const currentArrowPosition = this.getArrowPositionFromConnection(change.connectionPair);
      componentRef.setInput('arrowPosition', currentArrowPosition);
    });
  }

  private detachTooltipLayer() {
    this.overlayRef?.dispose();
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
