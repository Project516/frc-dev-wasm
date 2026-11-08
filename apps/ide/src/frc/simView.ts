import { FrameInput, openFrame } from './toolView'

export type SimGuiKind = 'wasm' | 'classic'

// The WPILib Sim GUI built to WebAssembly, one build per season, or the earlier hand-written one.
function simPage(kind: SimGuiKind, year: string): string {
  const path = kind === 'wasm' ? `runtime/${year}/tools/simgui/index.html` : 'sim.html'
  return new URL(`${import.meta.env.BASE_URL}${path}`, window.location.href).href
}

// Seasons whose WebAssembly Sim GUI is used; the others get the classic GUI
export const WASM_SIM_YEARS: readonly string[] = ['2026', '2027']

let current: FrameInput | undefined

class SimGuiInput extends FrameInput {
  constructor(kind: SimGuiKind, year: string) {
    super(`simgui-${kind}`, 'Simulation GUI', false)
    this.frame.src = simPage(kind, year)
  }

  override dispose(): void {
    if (current === this) current = undefined
    super.dispose()
  }
}

export async function openSimGui(kind: SimGuiKind, year: string): Promise<void> {
  current = await openFrame(`simgui-${kind}`, () => new SimGuiInput(kind, year))
}

export function closeSimGui(): void {
  current?.close()
}

export function getSimFrame(): HTMLIFrameElement | undefined {
  return current?.frame
}
