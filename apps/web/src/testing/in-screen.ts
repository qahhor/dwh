import { ChangeDetectorRef, type Injector } from '@angular/core';

/**
 * What a person sees: the component under test and the CDK overlay container,
 * where dialogs, menus and select lists render outside the component. Queries
 * look in the component first, then in the overlays; `textContent` is both.
 * Typed loosely, like `fixture.nativeElement`, so existing casts keep working.
 */
export interface Screen {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  querySelector(selector: string): any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  querySelectorAll(selector: string): any;
  readonly textContent: string;
  contains(node: Node | null): boolean;
}

export function inScreen(root: HTMLElement): Screen {
  const overlays = () => Array.from(document.querySelectorAll('.cdk-overlay-container')) as HTMLElement[];
  return {
    querySelector(selector: string): Element | null {
      return root.querySelector(selector) ?? overlays().map(overlay => overlay.querySelector(selector)).find(Boolean) ?? null;
    },
    querySelectorAll(selector: string): Element[] {
      return [root, ...overlays()].flatMap(node => Array.from(node.querySelectorAll(selector)));
    },
    get textContent(): string {
      return [root, ...overlays()].map(node => node.textContent ?? '').join('');
    },
    contains(node: Node | null): boolean {
      return root.contains(node) || overlays().some(overlay => overlay.contains(node));
    },
  };
}

/**
 * Change detection after a test changed a plain field of the component: the
 * component is marked for check first, as a person's event would, since an
 * OnPush view does not notice a field written from outside.
 */
export function redraw(fixture: { componentRef: { injector: Injector }; detectChanges(): void }): void {
  // The component's own view: componentRef.changeDetectorRef is the host view's.
  fixture.componentRef.injector.get(ChangeDetectorRef).markForCheck();
  fixture.detectChanges();
}
