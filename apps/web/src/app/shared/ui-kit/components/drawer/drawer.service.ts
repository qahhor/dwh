/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/drawer/drawer.service.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: focus returns to the element that opened the
 * drawer once it is gone; the overlay is disposed at once when there is no
 * leave animation to wait for; Escape is marked handled so a `ui-modal`
 * underneath does not close too; `afterClosed()` is a read-only
 * Observable; the container reads its config from its own token, so
 * `SMT_DRAWER_DATA` means the caller's data everywhere. */
import { Location } from '@angular/common';
import { ComponentRef, DestroyRef, Injectable, Injector, Type, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Overlay, OverlayConfig, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { Subject, filter } from 'rxjs';
import { SMTDrawerComponent } from './drawer.component';
import { DRAWER_ANIMATION_MS, drawerShouldAnimate } from './drawer-motion';
import { SMT_DRAWER_CONFIG, SMT_DRAWER_DATA, SMT_DRAWER_REF, SMTDrawerConfig, SMTDrawerRef } from './drawer.types';

@Injectable({
  providedIn: 'root',
})
export class SMTDrawerService {
  private readonly overlay = inject(Overlay);
  private readonly injector = inject(Injector);
  private readonly location = inject(Location);

  open<R = unknown, D = unknown>(component: Type<unknown>, config: SMTDrawerConfig<D> = {}): SMTDrawerRef<R> {
    const drawerConfig: SMTDrawerConfig<D> = {
      width: '90dvw',
      ...config,
    };
    const opener = typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null;

    const overlayConfig = new OverlayConfig({
      positionStrategy: this.overlay.position().global().top('0').left('0').bottom('0').right('0'),
      width: '100vw',
      height: '100dvh',
      maxWidth: '100vw',
      maxHeight: '100dvh',
      panelClass: 'smt-drawer-overlay-pane',
      hasBackdrop: true,
      backdropClass: 'smt-drawer-backdrop',
      scrollStrategy: this.overlay.scrollStrategies.block(),
      // Prefer Location.onUrlChange → drawerRef.close() below so afterClosed + leave
      // animation still run. CDK disposeOnNavigation only covers popstate and disposes raw.
      disposeOnNavigation: false,
    });

    const overlayRef = this.overlay.create(overlayConfig);

    const afterClosedSubject = new Subject<R | undefined>();
    let closed = false;
    let unlistenUrlChange: (() => void) | null = null;

    const drawerRef: SMTDrawerRef<R> = {
      close: (result?: R) => {
        if (closed) {
          return;
        }
        closed = true;
        unlistenUrlChange?.();
        unlistenUrlChange = null;
        this.closeDrawer(overlayRef, containerRef, opener);
        afterClosedSubject.next(result);
        afterClosedSubject.complete();
      },
      afterClosed: () => afterClosedSubject.asObservable(),
      componentInstance: null,
    };

    const containerPortal = new ComponentPortal(
      SMTDrawerComponent,
      null,
      this.createInjector(drawerRef as SMTDrawerRef, drawerConfig as SMTDrawerConfig)
    );
    const containerRef = overlayRef.attach(containerPortal);
    const drawerDestroyRef = containerRef.injector.get(DestroyRef);

    if (drawerConfig.closeOnBackdropClick === true) {
      overlayRef
        .backdropClick()
        .pipe(takeUntilDestroyed(drawerDestroyRef))
        .subscribe(() => containerRef.instance.close());
    }

    if (drawerConfig.closeOnEscape !== false) {
      overlayRef
        .keydownEvents()
        .pipe(
          filter((e: KeyboardEvent) => e.key === 'Escape'),
          takeUntilDestroyed(drawerDestroyRef)
        )
        .subscribe(e => {
          e.preventDefault();
          containerRef.instance.close();
        });
    }

    if (drawerConfig.closeOnNavigation !== false) {
      // Fires for Router.navigate (Location.go) and browser back (popstate).
      unlistenUrlChange = this.location.onUrlChange(() => drawerRef.close());
    }

    containerRef.changeDetectorRef.detectChanges();
    const contentRef = containerRef.instance.contentContainer().createComponent(component, {
      injector: this.createContentInjector(drawerRef as SMTDrawerRef, drawerConfig.data),
    });

    drawerRef.componentInstance = contentRef.instance;

    return drawerRef;
  }

  private createInjector(drawerRef: SMTDrawerRef, config: SMTDrawerConfig): Injector {
    return Injector.create({
      parent: this.injector,
      providers: [
        { provide: SMT_DRAWER_REF, useValue: drawerRef },
        { provide: SMT_DRAWER_CONFIG, useValue: config },
      ],
    });
  }

  private createContentInjector(drawerRef: SMTDrawerRef, data: unknown): Injector {
    return Injector.create({
      parent: this.injector,
      providers: [
        { provide: SMT_DRAWER_REF, useValue: drawerRef },
        { provide: SMT_DRAWER_DATA, useValue: data },
      ],
    });
  }

  private closeDrawer(
    overlayRef: OverlayRef,
    containerRef: ComponentRef<SMTDrawerComponent> | undefined,
    opener: HTMLElement | null
  ): void {
    const dispose = () => {
      overlayRef.dispose();
      if (opener?.isConnected && typeof opener.focus === 'function') opener.focus();
    };

    const animates = drawerShouldAnimate(overlayRef.overlayElement);
    if (containerRef?.instance && animates) {
      containerRef.setInput('isClosing', true);
      overlayRef.backdropElement?.classList.add('smt-drawer-backdrop--leaving');
      // Wait for the leave animation before disposing.
      setTimeout(dispose, DRAWER_ANIMATION_MS);
    } else {
      dispose();
    }
  }
}
