/* Our code: keeps a list of radio options the same array while what it is built from stays the same,
 * so a radio group does not re-render (and lose focus) on every check of a screen still on inputs. */

/** A cache for one list: pass what the list depends on and how to build it. */
export function optionsMemo<T>(): (deps: readonly unknown[], build: () => T) => T {
  let last: readonly unknown[] | null = null;
  let value: T;
  return (deps, build) => {
    if (!last || last.length !== deps.length || deps.some((dep, index) => !Object.is(dep, last![index]))) {
      last = deps;
      value = build();
    }
    return value;
  };
}
