// VS Code moves the focus into a quick input (command palette, pick, input box) a moment after it shows,
// so keys a test sends before that are lost. Wait for this before typing into one or pressing Enter in it.
export function quickInputReady(page, timeout = 15_000) {
  return page.waitForSelector('.quick-input-widget:not([style*="display: none"]) .quick-input-box input:focus', { timeout })
}
