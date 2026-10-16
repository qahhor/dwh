/** A download is acknowledged only once the browser has accepted its link click. */
export function downloadStudioJson(text: string, name: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
