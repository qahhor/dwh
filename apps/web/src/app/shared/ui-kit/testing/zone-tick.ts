/* Test helper. */
import { NgZone } from '@angular/core';
import { TestBed } from '@angular/core/testing';

/**
 * Runs change detection inside the Angular zone, as the application does.
 * Called from outside the zone, TestBed.tick() lets a render hook that moves
 * focus (the pickers and select do) run focus handlers as zone tasks; when
 * the zone settles it starts another tick inside the first one (NG0101).
 */
export function tickInZone(): void {
  TestBed.inject(NgZone).run(() => TestBed.tick());
}
