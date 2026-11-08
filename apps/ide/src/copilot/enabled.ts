const KEY = 'frc-dev-wasm.copilot'

// Chat loads with the workbench, so turning it on or off reloads the page
export const copilotEnabled = () => localStorage.getItem(KEY) === 'on'

export function setCopilotEnabled(on: boolean): void {
  if (on) localStorage.setItem(KEY, 'on')
  else localStorage.removeItem(KEY)
  location.reload()
}
