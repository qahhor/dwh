# UI Foundation, Entry, and Shell Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing shared UI primitives, authentication entry points, and application shells accessible, testable, responsive, and behaviorally consistent without adding product features.

**Architecture:** Add the Angular 22 native Vitest test target first, then harden shared primitives as contracts consumed by both entry flows and the instance shell. Keep API and domain behavior unchanged; use Angular CDK only where it is already an accepted dependency, and validate rendered behavior through the in-app Browser.

**Tech Stack:** Angular 22 standalone components, Angular CDK A11y, Vitest through `@angular/build:unit-test`, TypeScript 6, CSS custom properties, in-app Browser.

**Spec:** `docs/superpowers/specs/2026-08-30-ui-ux-hardening-design.md`

## Global Constraints

- Full acceptance at 1280 px and 1024 px; functional tablet acceptance at 768 px; 390 px is smoke-only.
- Do not add product modules, routes, workflows, backend APIs, phone navigation, gestures, or a visual rebrand.
- Preserve existing Angular, router, API-service, and domain contracts.
- Every production behavior change follows RED -> GREEN -> REFACTOR.
- Use real Angular components in tests; replace HTTP only at the request boundary.
- New styles use existing semantic tokens or new shared tokens for repeated decisions.
- Update Graphify only after code changes are complete.

## File structure locked by this plan

- `apps/web-instance/angular.json`: native unit-test target.
- `apps/web-instance/package.json`: deterministic non-watch test command.
- `apps/web-instance/src/styles.css`: shared focus, motion, and control tokens.
- `apps/web-instance/src/app/shared/ui/*.spec.ts`: component behavior contracts.
- `apps/web-instance/src/app/shared/ui/ui-button.component.ts`: action contract.
- `apps/web-instance/src/app/shared/ui/ui-modal.component.ts`: dialog/focus contract.
- `apps/web-instance/src/app/shared/ui/ui-toast.component.ts`: live status contract.
- `apps/web-instance/src/app/shared/ui/ui-searchable-select.component.ts`: combobox semantics.
- `apps/web-instance/src/app/shared/ui/ui-user-multi-select.component.ts`: multi-select semantics.
- `apps/web-instance/src/app/shared/ui/ui-pagination.component.ts`: navigation semantics.
- `apps/web-instance/src/app/shared/ui/ui-file-upload.component.ts`: keyboard-operable upload/download.
- `apps/web-instance/src/app/shared/ui/ui-markdown-editor.component.ts`: toolbar and editor semantics.
- `apps/web-instance/src/app/shared/ui/ui-custom-fields.component.ts`: generated form relationships.
- `apps/web-instance/src/app/features/auth/login/login.component.spec.ts`: entry-flow contract.
- `apps/web-instance/src/app/features/auth/login/login.component.ts`: login, OTP, and reset UX.
- `apps/web-instance/src/app/layout/app-shell/app-shell.component.spec.ts`: shell semantics.
- `apps/web-instance/src/app/layout/app-shell/app-shell.component.ts`: navigation and responsive shell.
- `apps/web-cp/angular.json`: native unit-test target.
- `apps/web-cp/package.json`: deterministic test command and supported Node engine.
- `apps/web-cp/src/app/pages/login.component.spec.ts`: control-plane entry contract.
- `apps/web-cp/src/app/pages/login.component.ts`: control-plane login semantics.
- `apps/web-cp/src/app/pages/shell.component.spec.ts`: control-plane shell contract.
- `apps/web-cp/src/app/pages/shell.component.ts`: control-plane navigation semantics.

---

### Task 1: Native Angular test targets and button contract

**Files:**
- Modify: `apps/web-instance/angular.json`
- Modify: `apps/web-instance/package.json`
- Modify: `apps/web-cp/angular.json`
- Modify: `apps/web-cp/package.json`
- Create: `apps/web-instance/src/app/shared/ui/ui-button.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-button.component.ts`

**Interfaces:**
- Produces: `UiButtonComponent.fullWidth: boolean`, `UiButtonComponent.ariaLabel?: string`, and the existing `onClick: EventEmitter<MouseEvent>`.
- Produces: `npm test` as a non-watch Vitest command in both SPA directories.

- [ ] **Step 1: Write the failing button contract test**

```ts
import { TestBed } from '@angular/core/testing';
import { UiButtonComponent } from './ui-button.component';

describe('UiButtonComponent', () => {
  it('exposes loading and full-width state on the native button', async () => {
    await TestBed.configureTestingModule({ imports: [UiButtonComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiButtonComponent);
    fixture.componentInstance.loading = true;
    fixture.componentInstance.fullWidth = true;
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.classList.contains('btn-full-width')).toBe(true);
    expect(button.querySelector('.sr-only')?.textContent).toContain('Выполняется');
  });

  it('provides an accessible name for icon-only buttons', async () => {
    await TestBed.configureTestingModule({ imports: [UiButtonComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiButtonComponent);
    fixture.componentInstance.icon = 'close';
    fixture.componentInstance.ariaLabel = 'Закрыть';
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe('Закрыть');
  });
});
```

- [ ] **Step 2: Configure the native test builders**

Add this target under each project's `architect` object:

```json
"test": {
  "builder": "@angular/build:unit-test",
  "options": {
    "runner": "vitest",
    "watch": false
  }
}
```

Use these package scripts:

```json
"test": "ng test --watch=false"
```

For `web-cp`, also document the already-required Angular CLI engine floor:

```json
"engines": {
  "node": "^22.22.3 || ^24.15.0 || >=26.0.0"
}
```

- [ ] **Step 3: Run the focused test and verify RED**

Run: `npm test -- --include src/app/shared/ui/ui-button.component.spec.ts`

Expected: FAIL because `fullWidth`, `ariaLabel`, `aria-busy`, and loading announcement do not exist.

- [ ] **Step 4: Implement the minimal button contract**

Add inputs and native attributes:

```ts
@Input() fullWidth = false;
@Input() ariaLabel?: string;
```

```html
<button
  [type]="type"
  [disabled]="disabled || loading"
  [attr.aria-busy]="loading ? 'true' : null"
  [attr.aria-label]="ariaLabel || null"
  [class]="'btn btn-' + variant + ' btn-' + size"
  [class.btn-full-width]="fullWidth"
  (click)="onClick.emit($event)"
>
  <span *ngIf="loading" class="spinner" aria-hidden="true"></span>
  <span *ngIf="loading" class="sr-only">Выполняется…</span>
  <span *ngIf="icon && !loading" class="material-symbols-outlined icon" aria-hidden="true">{{ icon }}</span>
  <ng-content></ng-content>
</button>
```

Add `.btn-full-width { width: 100%; }`, a reusable visually-hidden rule, and a token-based `:focus-visible` rule. Replace the hard-coded danger hover with `var(--danger-hover)` added in Task 2.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run: `npm test -- --include src/app/shared/ui/ui-button.component.spec.ts`

Expected: PASS with no Angular template warnings.

- [ ] **Step 6: Commit**

```powershell
git add -- apps/web-instance/angular.json apps/web-instance/package.json apps/web-instance/src/app/shared/ui/ui-button.component.ts apps/web-instance/src/app/shared/ui/ui-button.component.spec.ts apps/web-cp/angular.json apps/web-cp/package.json
git commit -m "test(ui): добавить нативный контур и контракт кнопок"
```

### Task 2: Global focus, motion, and repeated design tokens

**Files:**
- Modify: `apps/web-instance/src/styles.css`
- Modify: `apps/web-cp/src/styles.css`

**Interfaces:**
- Produces CSS variables `--focus-ring`, `--danger-hover`, `--control-min-size`, and global `.sr-only`.
- Consumed by all remaining tasks.

- [ ] **Step 1: Capture RED in the rendered login page**

Use the in-app Browser on `http://localhost:4200/login`, focus the submit control with Tab, and inspect computed styles:

```js
const button = tab.playwright.getByRole('button', { name: 'Войти в систему' });
await button.press('Tab');
const outline = await button.evaluate(el => getComputedStyle(el).outlineStyle);
```

Expected before the change: the shared button has no reliable visible focus indicator because its component CSS sets `outline: none`.

- [ ] **Step 2: Add tokens and global interaction rules**

Add to both light token blocks and dark overrides where color differs:

```css
--danger-hover: #b91c1c;
--focus-ring: #0ea5e9;
--control-min-size: 34px;
```

Add shared rules:

```css
:where(button, a, input, select, textarea, [tabindex]):focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    scroll-behavior: auto !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 3: Verify GREEN in the browser**

Reload the login page, focus the submit control, and verify `outlineStyle === 'solid'`, `outlineWidth === '2px'`, and no relevant console warnings.

- [ ] **Step 4: Commit**

```powershell
git add -- apps/web-instance/src/styles.css apps/web-cp/src/styles.css
git commit -m "fix(ui): восстановить видимый фокус и reduced motion"
```

### Task 3: Modal dialog focus and naming contract

**Files:**
- Create: `apps/web-instance/src/app/shared/ui/ui-modal.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-modal.component.ts`

**Interfaces:**
- Produces: `dismissible: boolean` input defaulting to `true`.
- Preserves: `isOpen`, `title`, `size`, `hasFooter`, and `close`.

- [ ] **Step 1: Write failing dialog tests**

```ts
import { TestBed } from '@angular/core/testing';
import { UiModalComponent } from './ui-modal.component';

describe('UiModalComponent', () => {
  it('labels the dialog with its visible title', async () => {
    await TestBed.configureTestingModule({ imports: [UiModalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiModalComponent);
    fixture.componentInstance.isOpen = true;
    fixture.componentInstance.title = 'Восстановление пароля';
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    const title = fixture.nativeElement.querySelector('h3') as HTMLElement;
    expect(title.id).not.toBe('');
    expect(dialog.getAttribute('aria-labelledby')).toBe(title.id);
  });

  it('does not close a non-dismissible dialog on Escape', async () => {
    await TestBed.configureTestingModule({ imports: [UiModalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiModalComponent);
    fixture.componentInstance.isOpen = true;
    fixture.componentInstance.dismissible = false;
    let closes = 0;
    fixture.componentInstance.close.subscribe(() => closes++);
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closes).toBe(0);
  });
});
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `npm test -- --include src/app/shared/ui/ui-modal.component.spec.ts`

Expected: FAIL because the title has no ID, the dialog has no `aria-labelledby`, and `dismissible` is missing.

- [ ] **Step 3: Implement CDK focus containment and dismissal rules**

Import `A11yModule`, generate a stable title ID, and apply CDK trapping:

```ts
import { A11yModule } from '@angular/cdk/a11y';

private static nextId = 0;
readonly titleId = `ui-modal-title-${UiModalComponent.nextId++}`;
@Input() dismissible = true;
```

```html
<div *ngIf="isOpen" class="modal-backdrop" (click)="onBackdropClick($event)">
  <div
    [class]="'modal-dialog modal-' + size"
    role="dialog"
    aria-modal="true"
    [attr.aria-labelledby]="titleId"
    cdkTrapFocus
    [cdkTrapFocusAutoCapture]="true"
  >
    <div class="modal-header">
      <h3 class="modal-title" [id]="titleId">{{ title }}</h3>
      <button *ngIf="dismissible" type="button" class="modal-close" (click)="close.emit()" aria-label="Закрыть">
        <span class="material-symbols-outlined" aria-hidden="true">close</span>
      </button>
    </div>
```

Gate Escape and backdrop close with `dismissible`; use `OnChanges`/`OnDestroy` to add and remove a `modal-open` class on `document.body`. Add `body.modal-open { overflow: hidden; }` to global CSS.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `npm test -- --include src/app/shared/ui/ui-modal.component.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-instance/src/app/shared/ui/ui-modal.component.ts apps/web-instance/src/app/shared/ui/ui-modal.component.spec.ts apps/web-instance/src/styles.css
git commit -m "fix(ui): укрепить доступность модальных окон"
```

### Task 4: Toast live-region contract

**Files:**
- Create: `apps/web-instance/src/app/shared/ui/ui-toast.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-toast.component.ts`

**Interfaces:**
- Preserves `ToastService` public API.
- Produces polite and assertive live regions with labelled dismiss buttons.

- [ ] **Step 1: Write the failing test**

```ts
import { TestBed } from '@angular/core/testing';
import { UiToastContainerComponent } from './ui-toast.component';
import { ToastService } from '../../core/services/toast.service';

describe('UiToastContainerComponent', () => {
  it('announces an error and names its dismiss action', async () => {
    await TestBed.configureTestingModule({ imports: [UiToastContainerComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiToastContainerComponent);
    TestBed.inject(ToastService).error('Сохранение не выполнено');
    fixture.detectChanges();

    const toast = fixture.nativeElement.querySelector('.toast-error') as HTMLElement;
    const close = fixture.nativeElement.querySelector('.toast-close') as HTMLButtonElement;
    expect(toast.getAttribute('role')).toBe('alert');
    expect(toast.getAttribute('aria-live')).toBe('assertive');
    expect(close.getAttribute('aria-label')).toBe('Закрыть уведомление');
  });
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- --include src/app/shared/ui/ui-toast.component.spec.ts`

Expected: FAIL on missing live-region attributes and accessible close name.

- [ ] **Step 3: Implement semantic announcements**

Remove the click handler and pointer cursor from `.toast-item`; set dynamic role/live attributes and label the close button:

```html
<div
  *ngFor="let toast of toastService.toasts()"
  [class]="'toast-item toast-' + toast.type"
  [attr.role]="toast.type === 'error' ? 'alert' : 'status'"
  [attr.aria-live]="toast.type === 'error' ? 'assertive' : 'polite'"
>
  <span class="material-symbols-outlined toast-icon" aria-hidden="true">{{ getIcon(toast.type) }}</span>
  <div class="toast-content">...</div>
  <button type="button" class="toast-close" aria-label="Закрыть уведомление" (click)="toastService.dismiss(toast.id)">...</button>
</div>
```

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- --include src/app/shared/ui/ui-toast.component.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-instance/src/app/shared/ui/ui-toast.component.ts apps/web-instance/src/app/shared/ui/ui-toast.component.spec.ts
git commit -m "fix(ui): сделать уведомления доступными live regions"
```

### Task 5: Instance login, OTP, and password-reset semantics

**Files:**
- Create: `apps/web-instance/src/app/features/auth/login/login.component.spec.ts`
- Modify: `apps/web-instance/src/app/features/auth/login/login.component.ts`

**Interfaces:**
- Preserves `AuthService.login`, `AuthService.verifyOtp`, and `/auth/password-reset/request`.
- Consumes `UiButtonComponent.fullWidth` and hardened `UiModalComponent`.

- [ ] **Step 1: Write failing entry tests**

Create test providers for `AuthService`, `ApiService`, and `ToastService`, then assert observable DOM behavior:

```ts
it('starts with an empty login and associated labels', () => {
  const login = fixture.nativeElement.querySelector('#login') as HTMLInputElement;
  const password = fixture.nativeElement.querySelector('#password') as HTMLInputElement;
  expect(login.value).toBe('');
  expect(fixture.nativeElement.querySelector('label[for="login"]')).not.toBeNull();
  expect(fixture.nativeElement.querySelector('label[for="password"]')).not.toBeNull();
  expect(password.autocomplete).toBe('current-password');
});

it('renders password recovery as a real button', () => {
  const trigger = fixture.nativeElement.querySelector('.forgot-link') as HTMLButtonElement;
  expect(trigger.tagName).toBe('BUTTON');
  expect(trigger.type).toBe('button');
});

it('exposes the OTP input contract', () => {
  fixture.componentInstance.step.set('otp');
  fixture.detectChanges();
  const otp = fixture.nativeElement.querySelector('#otp-code') as HTMLInputElement;
  expect(otp.inputMode).toBe('numeric');
  expect(otp.autocomplete).toBe('one-time-code');
  expect(otp.getAttribute('aria-describedby')).toBe('otp-hint');
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- --include src/app/features/auth/login/login.component.spec.ts`

Expected: FAIL because login defaults to `admin`, IDs/label relationships are missing, reset is a link without `href`, and OTP metadata is absent.

- [ ] **Step 3: Implement the entry contract**

- Initialize `login = ''` and keep all sensitive values empty.
- Add `id`/`for`, `aria-required`, and `aria-describedby` relationships.
- Replace the reset anchor with `<button type="button" class="forgot-link">`.
- Give OTP `inputmode="numeric"`, `autocomplete="one-time-code"`, and `pattern="[0-9]*"`.
- Use `[fullWidth]="true"` for primary credential and OTP actions.
- Give reset email `name`, `id`, `autocomplete="email"`, and an associated label.
- Expose request failures as inline form feedback with `role="alert"` while preserving toast success.

The primary input shape becomes:

```html
<label class="form-label" for="login">Логин или Email</label>
<input id="login" name="login" autocomplete="username" aria-required="true" ... />
```

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- --include src/app/features/auth/login/login.component.spec.ts`

Expected: PASS.

- [ ] **Step 5: Rendered desktop/tablet/mobile verification**

Flow under test: `/login` -> keyboard traversal and reset-dialog open/close -> focus returns to the reset trigger.

Verify at 1280, 1024, 768, and 390 px: no clipping; primary action is full width; labels are the accessible names; dialog has a visible title; Escape closes and restores focus; console has no relevant errors.

- [ ] **Step 6: Commit**

```powershell
git add -- apps/web-instance/src/app/features/auth/login/login.component.ts apps/web-instance/src/app/features/auth/login/login.component.spec.ts
git commit -m "fix(auth-ui): исправить вход OTP и восстановление пароля"
```

### Task 6: Instance shell semantics and accepted responsiveness

**Files:**
- Create: `apps/web-instance/src/app/layout/app-shell/app-shell.component.spec.ts`
- Modify: `apps/web-instance/src/app/layout/app-shell/app-shell.component.ts`

**Interfaces:**
- Preserves all permission checks and routes.
- Produces `main-content` landmark target and responsive compact sidebar CSS.

- [ ] **Step 1: Write failing shell tests**

With lightweight service fakes that mirror complete signal-returning interfaces, assert:

```ts
expect(fixture.nativeElement.querySelector('a.skip-link[href="#main-content"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('main#main-content')).not.toBeNull();
expect(fixture.nativeElement.querySelector('button[aria-label="Переключить тему"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('button[aria-label="Выйти из системы"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('nav[aria-label="Основная навигация"]')).not.toBeNull();
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- --include src/app/layout/app-shell/app-shell.component.spec.ts`

Expected: FAIL on missing skip link, landmark ID, nav name, and accessible icon-control names.

- [ ] **Step 3: Implement shell semantics**

- Add a skip link as the first focusable element.
- Name navigation and icon-only controls with `aria-label`.
- Mark decorative icons `aria-hidden="true"`.
- Add `aria-current="page"` through router-link active state where Angular permits, otherwise bind it from `RouterLinkActive.isActive`.
- Give notification count screen-reader text.
- Add `aria-label` and `aria-pressed` to language and theme controls.
- Give the announcement close button an accessible name and banner `role="status"`.
- Add `id="main-content"` and `tabindex="-1"` to main.

Add responsive CSS:

```css
@media (max-width: 1023px) {
  .sidebar { width: 60px; }
  .brand-logo, .nav-label, .nav-section-title, .user-meta, .unread-chip { display: none; }
  .palette-trigger { width: min(240px, 40vw); }
}

@media (max-width: 767px) {
  .topbar { padding-inline: 10px; }
  .trigger-text, .shortcut-kbd, .lang-selector { display: none; }
  .palette-trigger { width: 36px; padding: 6px; justify-content: center; }
  .page-content { padding: 12px; }
}
```

- [ ] **Step 4: Run and verify GREEN**

Run: `npm test -- --include src/app/layout/app-shell/app-shell.component.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-instance/src/app/layout/app-shell/app-shell.component.ts apps/web-instance/src/app/layout/app-shell/app-shell.component.spec.ts
git commit -m "fix(ui): укрепить навигацию и адаптивность shell"
```

### Task 7: Searchable and multi-user select keyboard contracts

**Files:**
- Create: `apps/web-instance/src/app/shared/ui/ui-searchable-select.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-searchable-select.component.ts`
- Create: `apps/web-instance/src/app/shared/ui/ui-user-multi-select.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-user-multi-select.component.ts`

**Interfaces:**
- Preserves existing inputs and selection outputs.
- Produces named combobox/listbox triggers with `aria-expanded`, `aria-controls`, and keyboard Escape handling.

- [ ] **Step 1: Write failing semantic tests**

Assert that each trigger is a native button or combobox with an accessible name, `aria-expanded="false"` initially, a stable controlled popup ID, and that Enter opens while Escape closes without changing the selected value.

```ts
trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
fixture.detectChanges();
expect(trigger.getAttribute('aria-expanded')).toBe('true');

document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
fixture.detectChanges();
expect(trigger.getAttribute('aria-expanded')).toBe('false');
```

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- --include src/app/shared/ui/ui-searchable-select.component.spec.ts --include src/app/shared/ui/ui-user-multi-select.component.spec.ts`

Expected: FAIL because the multi-select trigger is a clickable `div`, popup relationships are absent, and clear/remove actions lack names.

- [ ] **Step 3: Implement minimal keyboard and ARIA behavior**

- Replace the multi-select clickable `div` with a native button-like trigger while keeping tags outside nested button constraints.
- Add stable popup IDs and `aria-expanded`, `aria-controls`, and `aria-haspopup="listbox"`.
- Give search controls associated labels through `aria-label` when no visible label exists.
- Give clear/remove actions item-specific accessible names.
- Close on Escape and return focus to the trigger.
- Restore visible focus instead of `outline: none`.

- [ ] **Step 4: Run and verify GREEN**

Run the same focused command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-instance/src/app/shared/ui/ui-searchable-select.component.ts apps/web-instance/src/app/shared/ui/ui-searchable-select.component.spec.ts apps/web-instance/src/app/shared/ui/ui-user-multi-select.component.ts apps/web-instance/src/app/shared/ui/ui-user-multi-select.component.spec.ts
git commit -m "fix(ui): сделать селекты управляемыми с клавиатуры"
```

### Task 8: Pagination, file upload, markdown, and generated field semantics

**Files:**
- Create: `apps/web-instance/src/app/shared/ui/ui-pagination.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-pagination.component.ts`
- Create: `apps/web-instance/src/app/shared/ui/ui-file-upload.component.spec.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-file-upload.component.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-markdown-editor.component.ts`
- Modify: `apps/web-instance/src/app/shared/ui/ui-custom-fields.component.ts`

**Interfaces:**
- Preserves all value and output contracts.
- Produces named page controls, keyboard-operable file downloads, labelled toolbar controls, and generated `id`/`for` field relationships.

- [ ] **Step 1: Write failing pagination and upload tests**

```ts
expect(fixture.nativeElement.querySelector('nav[aria-label="Пагинация"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('button[aria-current="page"]')).not.toBeNull();
```

For file upload, assert the drop zone is a button with an accessible name and a file download control is a native button with a file-specific label.

- [ ] **Step 2: Run and verify RED**

Run: `npm test -- --include src/app/shared/ui/ui-pagination.component.spec.ts --include src/app/shared/ui/ui-file-upload.component.spec.ts`

Expected: FAIL on missing navigation names, current-page semantics, and non-semantic file row activation.

- [ ] **Step 3: Implement semantic controls**

- Wrap pagination controls in `<nav aria-label="Пагинация">`.
- Label first/previous/next/last buttons and set `aria-current="page"` on the current page.
- Associate page-size text with its select.
- Make the upload drop zone keyboard-operable using a native button or labelled input trigger.
- Replace clickable file info with a button/link and label download/remove actions with the file name.
- Add `aria-label` to every markdown toolbar icon button using the existing Russian `title` copy; expose edit/preview as a tablist with selected state.
- Generate IDs as `custom-field-${f.id}` and associate all visible custom-field labels.
- Remove component-local `outline: none` rules or replace them with `:focus-visible` token rules.

- [ ] **Step 4: Run focused and complete shared-UI tests**

Run: `npm test -- --include src/app/shared/ui/**/*.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-instance/src/app/shared/ui
git commit -m "fix(ui): унифицировать доступность сложных контролов"
```

### Task 9: Control-plane entry and shell semantics

**Files:**
- Create: `apps/web-cp/src/app/pages/login.component.spec.ts`
- Modify: `apps/web-cp/src/app/pages/login.component.ts`
- Create: `apps/web-cp/src/app/pages/shell.component.spec.ts`
- Modify: `apps/web-cp/src/app/pages/shell.component.ts`

**Interfaces:**
- Preserves `CpApiService.login`, logout, routes, and roles.
- Produces associated login labels, named navigation, current-route state, and accessible logout.

- [ ] **Step 1: Write failing CP tests**

```ts
expect(fixture.nativeElement.querySelector('label[for="cp-login"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('label[for="cp-password"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('nav[aria-label="Навигация Control Panel"]')).not.toBeNull();
expect(fixture.nativeElement.querySelector('button[aria-label="Выйти из Control Panel"]')).not.toBeNull();
```

- [ ] **Step 2: Run and verify RED under a supported Node runtime**

Run with Node 22.22.3+ or 24.15.0+: `npm test -- --include src/app/pages/login.component.spec.ts --include src/app/pages/shell.component.spec.ts`

Expected: FAIL on missing form relationships and navigation names. If the current host remains Node 24.14.0, record the runtime as an external execution blocker; do not weaken Angular's engine requirement.

- [ ] **Step 3: Implement CP semantics and responsive containment**

- Add stable IDs and `for` attributes to login controls.
- Add `aria-busy` and inline error association to the login form.
- Name the shell nav, add `aria-current`, and label logout.
- Mark decorative initials/icons hidden where applicable.
- Add horizontal table containment and page-head wrapping at 768 px in `apps/web-cp/src/styles.css`.

- [ ] **Step 4: Run tests and production build**

Run under a supported Node runtime:

```powershell
npm test
npm run build
```

Expected: both commands PASS.

- [ ] **Step 5: Commit**

```powershell
git add -- apps/web-cp
git commit -m "fix(cp-ui): укрепить вход и навигационный shell"
```

### Task 10: Foundation verification and first audit checkpoint

**Files:**
- Create: `audit/widgets-2026-08-30.md`
- Modify only if verification exposes a regression: files changed in Tasks 1-9, with a new failing test first.

**Interfaces:**
- Produces the first coverage rows and evidence used by later feature-screen plans.

- [ ] **Step 1: Run complete instance tests and build**

```powershell
Set-Location apps/web-instance
npm test
npm run build
```

Expected: PASS, no relevant warnings, initial bundle within the existing 500 kB warning budget and 1 MB error budget.

- [ ] **Step 2: Run CP tests and build on the supported runtime**

```powershell
Set-Location apps/web-cp
npm test
npm run build
```

Expected: PASS. If the host runtime is still 24.14.0, the report records the exact unsupported-runtime blocker and the successful rendered checks from the already-running dev service without claiming a local production-build pass.

- [ ] **Step 3: Run rendered Browser QA**

Flow under test: app loads -> login renders -> keyboard opens and closes reset dialog -> visible focus and state remain correct. Also load CP login and exercise focus traversal.

Required checks for both apps:

- Page URL/title identity.
- Non-blank DOM.
- No Angular/Vite/Webpack overlay.
- No relevant console errors or warnings.
- Screenshots at 1280, 1024, 768, and 390 px.
- Interaction proof for keyboard focus and dialog open/close.

- [ ] **Step 4: Write the audit checkpoint**

Create `audit/widgets-2026-08-30.md` with:

- Executive summary and maturity score.
- Route coverage table for every route in both SPAs.
- Validated foundation, login, and shell findings with severity, file, fix, risk, test, and rendered evidence.
- Scorecards for a11y, responsive behavior, loading/error/empty states, i18n, performance, and consistency.
- Explicit `Pending feature-plan verification` status for routed feature screens not yet migrated; do not mark them fixed.

- [ ] **Step 5: Update Graphify and verify the worktree**

Run:

```powershell
graphify update .
git diff --check
git status --short
```

Expected: graph update succeeds; no whitespace errors; only intentional graph artifacts and the audit checkpoint remain unstaged.

- [ ] **Step 6: Commit**

```powershell
git add -- audit/widgets-2026-08-30.md graphify-out
git commit -m "docs(ui): зафиксировать проверку UI-фундамента"
```

## Follow-on plans

After this plan passes, write and execute two independent plans against the stable contracts:

1. `web-instance` routed feature screens in this order: tasks/projects; users/roles/profile; files/notifications; audit/custom-fields/settings.
2. `web-cp` operational screens: fleet, clients, backups, announcements.

Each follow-on plan must reuse the test target and UI contracts created here and must add a failing test before each behavior change.

