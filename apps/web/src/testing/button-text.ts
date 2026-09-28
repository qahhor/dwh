/**
 * A button's text as a person reads it: the icon's ligature ("add", "delete")
 * and Angular's comment anchors are left out, so `smt-button` with `smtIcon`
 * compares with the words on it.
 */
export function buttonText(button: Element): string {
  return Array.from(button.childNodes)
    .filter(
      (node) =>
        node.nodeType === Node.TEXT_NODE ||
        (node instanceof Element && !node.classList.contains('material-symbols-outlined')),
    )
    .map((node) => node.textContent)
    .join('')
    .trim();
}
