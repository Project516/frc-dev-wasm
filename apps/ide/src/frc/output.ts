import type * as vscode from 'vscode'

const NAME = 'WPILib'

// A pseudoterminal that build and simulation output goes to, mirrored in an output channel.
export function createOutput(api: typeof vscode) {
  const channel = api.window.createOutputChannel(NAME)
  const emitter = new api.EventEmitter<string>()
  let terminal: vscode.Terminal | undefined
  let opened = false
  let pending: string[] = []

  const pty: vscode.Pseudoterminal = {
    onDidWrite: emitter.event,
    open() {
      opened = true
      for (const text of pending) emitter.fire(text)
      pending = []
    },
    close() {
      terminal = undefined
      opened = false
    }
  }

  function ensureTerminal(): vscode.Terminal {
    terminal ??= api.window.createTerminal({ name: NAME, pty })
    return terminal
  }

  return {
    // Output channel only, for messages that should not open the terminal
    log(text: string) {
      channel.append(text)
    },
    write(text: string) {
      channel.append(text)
      ensureTerminal()
      const data = text.replace(/\r?\n/g, '\r\n')
      if (opened) emitter.fire(data)
      else pending.push(data)
    },
    showTerminal() {
      ensureTerminal().show()
    }
  }
}
