/* Adapter, not vendored code.
 *
 * The kit registers SVG sprites before use. Material Symbols are a font, so
 * there is nothing to register; this keeps the call sites in the vendored
 * components compiling and does nothing at runtime. */
export function injectRegisterSMTIcons(_icons: readonly unknown[]): void {
  // Intentionally empty: a font needs no per-icon registration.
}
