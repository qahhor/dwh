const ALLOWED_ABSOLUTE_SCHEMES = /^(?:https?:\/\/|mailto:)/i;
const ALLOWED_RELATIVE_TARGET = /^(?:\/(?!\/)|\.\.?\/(?!\/)|#[^\s]*)/;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

export function replaceMarkdownLinksWithSafeAnchors(html: string): string {
  return html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_match, label: string, candidate: string) => {
    const href = candidate.trim();
    if (!isAllowedMarkdownHref(href)) {
      return `${label} (${candidate})`;
    }
    return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`;
  });
}

export function isAllowedMarkdownHref(href: string): boolean {
  if (!href || CONTROL_CHARACTERS.test(href)) {
    return false;
  }
  return ALLOWED_ABSOLUTE_SCHEMES.test(href) || ALLOWED_RELATIVE_TARGET.test(href);
}
