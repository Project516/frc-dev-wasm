import type { WpilibHost } from '../host'

let current: WpilibHost | undefined

// Waits for the IDE, which provides the host once the workbench has started
export async function connectHost(): Promise<WpilibHost> {
  const pending = (globalThis as { frcDevWasmWpilib?: Promise<WpilibHost> }).frcDevWasmWpilib
  if (!pending) throw new Error('The WPILib extension runs only inside FRC Dev')
  return (current = await pending)
}

export function host(): WpilibHost {
  if (!current) throw new Error('The WPILib extension runs only inside FRC Dev')
  return current
}
