/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path directives/hotkey/hotkey.directive.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: the host gets `aria-keyshortcuts`, so assistive
 * technology announces the shortcut; the kit's `alt+q` "close" preset was
 * dropped because nothing here closes pages with it. */
import { booleanAttribute, Directive, effect, ElementRef, inject, input, OnDestroy } from '@angular/core';
import { formatHotkey, SMTHotkeyRef, SMTHotkeysService } from '../../services/hotkeys.service';

const DEFAULT_ACTIONS: Record<string, string> = {
  add: 'alt+a',
  edit: 'alt+e',
  save: 'alt+s',
  finish: 'ctrl+enter',
  refresh: 'alt+r',
  delete: 'alt+d',
};

/**
 * Clicks the host when its shortcut is pressed. Accepts a combination
 * (`ctrl+enter`) or a preset name (`save` → Alt+S).
 *
 *   <button type="button" smtHotkey="save" (click)="save()">…</button>
 */
@Directive({
  selector: '[smtHotkey]',
  standalone: true,
  host: {
    '[attr.aria-keyshortcuts]': 'ariaKeyShortcuts()',
  },
})
export class SMTHotkeyDirective implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly hotkeys = inject(SMTHotkeysService);

  hotkey = input('', { alias: 'smtHotkey' });
  enabled = input(true, { alias: 'smtHotkeyEnabled', transform: booleanAttribute });
  allowInInputs = input(false, { alias: 'smtHotkeyAllowInInputs', transform: booleanAttribute });
  preventDefault = input(true, { alias: 'smtHotkeyPreventDefault', transform: booleanAttribute });
  priority = input(0, { alias: 'smtHotkeyPriority' });

  private hotkeyRef: SMTHotkeyRef | null = null;

  constructor() {
    effect(() => this.setupOrUpdateRegistration());
  }

  ngOnDestroy(): void {
    this.hotkeyRef?.unregister();
  }

  ariaKeyShortcuts(): string | null {
    const mapped = this.mappedKey();
    return mapped && this.enabled() ? formatHotkey(mapped) : null;
  }

  private mappedKey(): string {
    const raw = this.hotkey().trim().toLowerCase();
    return DEFAULT_ACTIONS[raw] ?? raw;
  }

  private setupOrUpdateRegistration(): void {
    const host = this.host.nativeElement;
    const mapped = this.mappedKey();

    this.hotkeyRef?.unregister();
    this.hotkeyRef = null;
    if (!mapped) return;

    this.hotkeyRef = this.hotkeys.register({
      key: mapped,
      priority: this.priority(),
      allowInInputs: this.allowInInputs(),
      preventDefault: this.preventDefault(),
      enabled: () => this.enabled() && this.isClickable(host),
      element: host,
      action: () => host.click(),
    });
  }

  private isClickable(element: HTMLElement): boolean {
    if (!element.isConnected) return false;
    const disabled = element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true';
    return !disabled;
  }
}
