/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path services/hotkeys.service.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit:
 * - The physical key (`KeyboardEvent.code`) decides the letter or digit for
 *   every Ctrl, Alt or Meta combination, not only for Alt. On a Russian
 *   layout Ctrl+S arrives as `key: 'ы'`; the kit never matched it.
 * - While a modal dialog is open, a hotkey bound to an element fires only
 *   when that element is inside the topmost modal, so a shortcut cannot
 *   press a button on the page underneath.
 * - `formatHotkey()` gives the `aria-keyshortcuts` form of a combination. */
import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { DestroyRef, inject, Injectable, PLATFORM_ID } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { fromEvent } from 'rxjs';

export interface SMTHotkeyRegistration {
  key: string;
  action: (event: KeyboardEvent) => void;
  enabled?: boolean | (() => boolean);
  allowInInputs?: boolean;
  preventDefault?: boolean;
  stopPropagation?: boolean;
  repeat?: boolean;
  priority?: number;
  element?: HTMLElement | null;
}

export interface SMTHotkeyRef {
  unregister: () => void;
}

export const DEFAULT_HOTKEYS = {
  global: {
    f2: 'openGlobalSearch',
  },
  toolbar: {
    'alt+a': 'add',
    'alt+e': 'edit',
    'alt+s': 'save',
    'ctrl+enter': 'finish',
    'alt+r': 'refresh',
    'alt+d': 'delete',
  },
} as const;

interface InternalRegistration {
  id: number;
  normalizedKey: string;
  config: Required<
    Pick<SMTHotkeyRegistration, 'allowInInputs' | 'preventDefault' | 'stopPropagation' | 'repeat' | 'priority'>
  > &
    Omit<SMTHotkeyRegistration, 'allowInInputs' | 'preventDefault' | 'stopPropagation' | 'repeat' | 'priority'>;
}

const MODIFIER_KEYS = new Set(['ctrl', 'alt', 'shift', 'meta']);

const MODIFIER_LABELS: Record<string, string> = { ctrl: 'Control', alt: 'Alt', shift: 'Shift', meta: 'Meta' };

/** `event.code` for function keys (same on Windows/macOS); `event.key` can be empty / Unidentified on WebKit. */
const FUNCTION_KEY_CODE_TO_NAME: Record<string, string> = Object.fromEntries(
  Array.from({ length: 24 }, (_, i) => [`F${i + 1}`, `f${i + 1}`])
);

/** Legacy `keyCode` for F1–F12 (still useful when `key`/`code` are inconsistent). */
const FUNCTION_KEY_LEGACY_KEYCODE_TO_NAME: Record<number, string> = {
  112: 'f1',
  113: 'f2',
  114: 'f3',
  115: 'f4',
  116: 'f5',
  117: 'f6',
  118: 'f7',
  119: 'f8',
  120: 'f9',
  121: 'f10',
  122: 'f11',
  123: 'f12',
};

function normalizeSingleKey(rawKey: string): string {
  const key = rawKey.toLowerCase();
  if (key === 'control') return 'ctrl';
  if (key === ' ') return 'space';
  if (key === 'esc') return 'escape';
  if (key === 'arrowup') return 'up';
  if (key === 'arrowdown') return 'down';
  if (key === 'arrowleft') return 'left';
  if (key === 'arrowright') return 'right';
  if (key === 'return') return 'enter';
  return key;
}

/** Canonical form of a combination: modifiers in a fixed order, then the key. */
export function normalizeHotkey(rawKey: string): string {
  const tokens = rawKey
    .toLowerCase()
    .split('+')
    .map(k => k.trim())
    .filter(Boolean)
    .map(k => normalizeSingleKey(k));

  const modifiers = ['ctrl', 'alt', 'shift', 'meta'].filter(mod => tokens.includes(mod));
  const base = tokens.find(token => !MODIFIER_KEYS.has(token));
  return [...modifiers, ...(base ? [base] : [])].join('+');
}

/** The combination as `aria-keyshortcuts` spells it, e.g. `alt+s` → `Alt+S`. Not in the kit. */
export function formatHotkey(rawKey: string): string {
  return normalizeHotkey(rawKey)
    .split('+')
    .filter(Boolean)
    .map(token => {
      if (MODIFIER_LABELS[token]) return MODIFIER_LABELS[token];
      if (token === 'escape') return 'Escape';
      if (token === 'space') return 'Space';
      if (token === 'enter') return 'Enter';
      if (['up', 'down', 'left', 'right'].includes(token)) return `Arrow${token[0].toUpperCase()}${token.slice(1)}`;
      return token.toUpperCase();
    })
    .join('+');
}

@Injectable({
  providedIn: 'root',
})
export class SMTHotkeysService {
  private readonly document = inject(DOCUMENT);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  private readonly hotkeysByCombo = new Map<string, InternalRegistration[]>();
  private idCounter = 0;

  constructor() {
    if (!isPlatformBrowser(this.platformId)) return;
    fromEvent<KeyboardEvent>(this.document, 'keydown')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(event => this.handleKeydown(event));
  }

  register(registration: SMTHotkeyRegistration): SMTHotkeyRef {
    const normalizedKey = normalizeHotkey(registration.key);
    const item: InternalRegistration = {
      id: ++this.idCounter,
      normalizedKey,
      config: {
        ...registration,
        allowInInputs: registration.allowInInputs ?? false,
        preventDefault: registration.preventDefault ?? true,
        stopPropagation: registration.stopPropagation ?? true,
        repeat: registration.repeat ?? false,
        priority: registration.priority ?? 0,
      },
    };

    const current = this.hotkeysByCombo.get(normalizedKey) ?? [];
    current.push(item);
    this.hotkeysByCombo.set(normalizedKey, current);

    return {
      unregister: () => this.unregister(item),
    };
  }

  registerMany(registrations: SMTHotkeyRegistration[]): SMTHotkeyRef {
    const refs = registrations.map(item => this.register(item));
    return {
      unregister: () => refs.forEach(ref => ref.unregister()),
    };
  }

  private unregister(target: InternalRegistration): void {
    const current = this.hotkeysByCombo.get(target.normalizedKey);
    if (!current) return;
    const next = current.filter(item => item.id !== target.id);
    if (next.length === 0) {
      this.hotkeysByCombo.delete(target.normalizedKey);
      return;
    }
    this.hotkeysByCombo.set(target.normalizedKey, next);
  }

  private handleKeydown(event: KeyboardEvent): void {
    const normalizedEventKey = this.normalizeEvent(event);
    const matches = this.hotkeysByCombo.get(normalizedEventKey);
    if (!matches?.length) return;

    const modal = this.topmostModal();
    const candidate = matches
      .filter(item => this.isEnabled(item.config.enabled))
      .filter(item => (item.config.repeat ? true : !event.repeat))
      .filter(item => (item.config.allowInInputs ? true : !this.isInputLikeTarget(event.target)))
      .filter(item => (item.config.element ? this.isElementAllowed(item.config.element, modal) : true))
      .sort((a, b) => {
        const byPriority = b.config.priority - a.config.priority;
        if (byPriority !== 0) return byPriority;
        return b.id - a.id;
      })[0];

    if (!candidate) return;
    if (candidate.config.preventDefault) event.preventDefault();
    if (candidate.config.stopPropagation) event.stopPropagation();
    candidate.config.action(event);
  }

  private isEnabled(enabled: SMTHotkeyRegistration['enabled']): boolean {
    if (typeof enabled === 'function') return enabled();
    if (typeof enabled === 'boolean') return enabled;
    return true;
  }

  private topmostModal(): Element | null {
    const modals = this.document.querySelectorAll('[aria-modal="true"]');
    return modals.length ? modals[modals.length - 1] : null;
  }

  private isElementAllowed(element: HTMLElement, modal: Element | null): boolean {
    if (!element.isConnected) return false;
    if (element.closest('[aria-hidden="true"], [inert]')) return false;
    if (modal && !modal.contains(element)) return false;
    return this.isVisible(element);
  }

  private isVisible(element: HTMLElement): boolean {
    return !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);
  }

  private isInputLikeTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    if (target.isContentEditable) return true;
    const tag = target.tagName.toLowerCase();
    return tag === 'input' || tag === 'textarea' || tag === 'select';
  }

  private normalizeEvent(event: KeyboardEvent): string {
    const segments: string[] = [];
    if (event.ctrlKey) segments.push('ctrl');
    if (event.altKey) segments.push('alt');
    if (event.shiftKey) segments.push('shift');
    if (event.metaKey) segments.push('meta');

    const mainKey = this.mainKeyFromKeyboardEvent(event);
    if (mainKey && !MODIFIER_KEYS.has(mainKey)) segments.push(mainKey);
    return segments.join('+');
  }

  /**
   * Physical key token from `KeyboardEvent.code` (US QWERTY positions). Used when a modifier or the
   * layout changes `key`: Mac Option+A → "å", Russian Ctrl+S → "ы"; `code` stays `KeyA` / `KeyS`.
   */
  private baseKeyFromEventCode(code: string): string | null {
    const fnKey = FUNCTION_KEY_CODE_TO_NAME[code];
    if (fnKey) return fnKey;
    if (code.length === 4 && code.startsWith('Key')) {
      const ch = code.charAt(3);
      if (ch >= 'A' && ch <= 'Z') return ch.toLowerCase();
    }
    if (code.length === 6 && code.startsWith('Digit')) {
      return code.slice(5);
    }
    return null;
  }

  /**
   * Resolve the non-modifier key token. With Ctrl, Alt or Meta held the physical key wins, so
   * shortcuts do not depend on the keyboard layout. Otherwise prefer `KeyboardEvent.key`; on Mac
   * Safari/WebKit, function keys may report `key` as empty or Unidentified — then use `code` or
   * legacy `keyCode`.
   */
  private mainKeyFromKeyboardEvent(event: KeyboardEvent): string {
    if (event.ctrlKey || event.altKey || event.metaKey) {
      const fromCode = this.baseKeyFromEventCode(event.code);
      if (fromCode) return fromCode;
    }

    const rawKey = event.key;
    const fromKey = rawKey ? normalizeSingleKey(rawKey) : '';
    const keyUnreliable =
      !fromKey || fromKey === 'unidentified' || fromKey === 'dead' || (fromKey === 'process' && rawKey === 'Process');

    if (!keyUnreliable && !MODIFIER_KEYS.has(fromKey)) {
      return fromKey;
    }

    const fromCode = FUNCTION_KEY_CODE_TO_NAME[event.code];
    if (fromCode) return fromCode;

    const fromLegacy = FUNCTION_KEY_LEGACY_KEYCODE_TO_NAME[event.keyCode];
    if (fromLegacy) return fromLegacy;

    if (fromKey && !MODIFIER_KEYS.has(fromKey)) return fromKey;
    return '';
  }
}
