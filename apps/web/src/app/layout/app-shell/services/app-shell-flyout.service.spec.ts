import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NavSection } from '../app-shell.models';
import { AppShellFlyoutService } from './app-shell-flyout.service';

const IAM: NavSection = {
  id: 'iam',
  titleKey: 'nav.section.iam',
  items: [{ id: 'users', route: '/iam/users', labelKey: 'nav.users', icon: 'group', permission: () => true }],
};
const ADMIN: NavSection = { id: 'administration', titleKey: 'nav.section.administration', items: [] };

/** A pointer event over an element whose top edge is at `top`. */
function over(top: number): MouseEvent {
  const target = document.createElement('button');
  target.getBoundingClientRect = () => ({ top }) as DOMRect;
  return { currentTarget: target, target, stopPropagation: vi.fn() } as unknown as MouseEvent;
}

describe('AppShellFlyoutService', () => {
  let flyout: AppShellFlyoutService;

  beforeEach(() => {
    vi.useFakeTimers();
    flyout = TestBed.inject(AppShellFlyoutService);
  });
  afterEach(() => vi.useRealTimers());

  it('opens a section only on the collapsed desktop rail, after a short hover, and closes it after leaving', () => {
    flyout.onCategoryMouseEnter(IAM, over(150), false, false);
    flyout.onCategoryMouseEnter(IAM, over(150), true, true);
    vi.advanceTimersByTime(100);
    expect(flyout.isFlyoutVisible()).toBe(false);

    flyout.onCategoryMouseEnter(IAM, over(150), true, false);
    vi.advanceTimersByTime(59);
    expect(flyout.isFlyoutVisible()).toBe(false);
    vi.advanceTimersByTime(1);
    expect(flyout.isFlyoutVisible()).toBe(true);
    expect(flyout.hoveredFlyoutSection()).toBe(IAM);
    expect(flyout.flyoutAnchorTop()).toBe(150);

    flyout.onCategoryMouseLeave();
    flyout.onFlyoutMouseEnter(); // the pointer moved onto the flyout in time
    vi.advanceTimersByTime(500);
    expect(flyout.isFlyoutVisible()).toBe(true);

    flyout.onFlyoutMouseLeave();
    vi.advanceTimersByTime(150);
    expect(flyout.isFlyoutVisible()).toBe(false);
    expect(flyout.hoveredFlyoutSection()).toBeNull();
  });

  it('opens an item flyout next to it within the viewport and cancels it when the pointer leaves first', () => {
    const item = IAM.items[0];
    flyout.onItemMouseEnter(IAM, item, over(2), true, false);
    flyout.onItemMouseLeave();
    vi.advanceTimersByTime(500);
    expect(flyout.isFlyoutVisible()).toBe(false);

    flyout.onItemMouseEnter(IAM, item, over(2), true, false);
    vi.advanceTimersByTime(80);
    expect(flyout.isFlyoutVisible()).toBe(true);
    expect(flyout.hoveredFlyoutItem()).toBe(item);
    expect(flyout.flyoutAnchorTop()).toBe(8);

    flyout.onItemMouseEnter(IAM, item, over(150), false, false);
    expect(flyout.isFlyoutVisible()).toBe(true);
  });

  it('toggles a section by click, switches to another one and closes on a click outside the rail', () => {
    const click = over(40);
    flyout.onCategoryClick(IAM, click);
    expect(click.stopPropagation).toHaveBeenCalled();
    expect(flyout.hoveredFlyoutSection()).toBe(IAM);

    flyout.onCategoryClick(ADMIN, over(40));
    expect(flyout.hoveredFlyoutSection()).toBe(ADMIN);
    flyout.onCategoryClick(ADMIN, over(40));
    expect(flyout.isFlyoutVisible()).toBe(false);

    flyout.onCategoryClick(IAM, over(40));
    const inside = document.createElement('div');
    inside.className = 'rail-flyout-popover';
    document.body.appendChild(inside);
    flyout.onDocumentClick({ target: inside } as unknown as MouseEvent);
    expect(flyout.isFlyoutVisible()).toBe(true);
    flyout.onDocumentClick({ target: document.body } as unknown as MouseEvent);
    expect(flyout.isFlyoutVisible()).toBe(false);
    inside.remove();
  });

  it('shows the profile menu instead of a section, keeps it while hovered and closes it after a followed link', () => {
    const navigated = vi.fn();
    flyout.onCategoryClick(IAM, over(40));
    flyout.onProfileMouseEnter(over(600), false, false);
    expect(flyout.isProfileFlyoutVisible()).toBe(false);

    flyout.onProfileMouseEnter(over(600), true, false);
    expect(flyout.isProfileFlyoutVisible()).toBe(true);
    expect(flyout.isFlyoutVisible()).toBe(false);

    flyout.onProfileMouseLeave();
    flyout.onProfileFlyoutMouseEnter();
    vi.advanceTimersByTime(500);
    expect(flyout.isProfileFlyoutVisible()).toBe(true);
    flyout.onProfileFlyoutMouseLeave();
    vi.advanceTimersByTime(150);
    expect(flyout.isProfileFlyoutVisible()).toBe(false);

    flyout.onProfileMouseEnter(over(600), true, false);
    flyout.onProfileFlyoutClick(navigated);
    expect(flyout.isProfileFlyoutVisible()).toBe(false);
    flyout.onCategoryClick(IAM, over(40));
    flyout.onFlyoutItemClick(navigated);
    expect(flyout.isFlyoutVisible()).toBe(false);
    expect(navigated).toHaveBeenCalledTimes(2);
  });
});
