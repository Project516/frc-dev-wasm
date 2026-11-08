import { IEditorGroupsService, IEditorService, ILayoutService, StandaloneServices } from '@codingame/monaco-vscode-api'
import type { IEditorGroup } from '@codingame/monaco-vscode-api'
import type { SwingApp } from '@frc-dev-wasm/runtime'
import {
  SimpleEditorInput,
  SimpleEditorPane,
  registerEditorPane
} from '@codingame/monaco-vscode-workbench-service-override/common'
import { forwardKeys } from './toolKeys'

// The workbench detaches a pane's DOM whenever another editor is shown, which reloads an
// iframe inside it and loses the tool's state (a JVM does not survive it at all). So each
// input owns an iframe in the workbench container that follows the pane's placeholder while the
// tab is visible. In the container, the command palette, menus and dialogs stay above it.
const HIDDEN = 'position:fixed;z-index:1;border:0;visibility:hidden'

const inputs = new Map<string, FrameInput>()

export class FrameInput extends SimpleEditorInput {
  readonly frame = document.createElement('iframe')

  constructor(
    readonly key: string,
    name: string,
    swingKeys: boolean
  ) {
    super(undefined)
    this.setName(name)
    this.frame.title = name
    this.frame.style.cssText = HIDDEN
    StandaloneServices.get(ILayoutService).mainContainer.append(this.frame)
    forwardKeys(this.frame, swingKeys)
  }

  get typeId(): string {
    return FramePane.ID
  }

  override matches(other: unknown): boolean {
    return other instanceof FrameInput && other.key === this.key
  }

  showOver(placeholder: HTMLElement) {
    const style = this.frame.style
    let frameId = 0
    let last = ''
    const follow = () => {
      const { left, top, width, height } = placeholder.getBoundingClientRect()
      const box = `${left} ${top} ${width} ${height}`
      if (box !== last) {
        last = box
        style.cssText = `position:fixed;z-index:1;border:0;left:${left}px;top:${top}px;width:${width}px;height:${height}px`
      }
      frameId = requestAnimationFrame(follow)
    }
    follow()
    return {
      dispose: () => {
        cancelAnimationFrame(frameId)
        style.visibility = 'hidden'
      }
    }
  }

  close(): void {
    for (const group of StandaloneServices.get(IEditorGroupsService).groups) void group.closeEditor(this)
  }

  override dispose(): void {
    if (inputs.get(this.key) === this) inputs.delete(this.key)
    this.frame.remove()
    super.dispose()
  }
}

class FramePane extends SimpleEditorPane {
  static readonly ID = 'frc.frame'
  private placeholder?: HTMLElement

  constructor(group: IEditorGroup) {
    super(FramePane.ID, group)
  }

  initialize(): HTMLElement {
    this.placeholder = document.createElement('div')
    this.placeholder.style.cssText = 'width:100%;height:100%'
    return this.placeholder
  }

  async renderInput(input: unknown) {
    if (!(input instanceof FrameInput) || !this.placeholder) return { dispose() {} }
    return input.showOver(this.placeholder)
  }

  override focus(): void {
    if (this.input instanceof FrameInput) this.input.frame.contentWindow?.focus()
  }
}

registerEditorPane('frc-frame-pane', 'WPILib Tool', FramePane, [FrameInput])

// Shows the tab for key, creating its input the first time. An open tab keeps its page.
export async function openFrame(key: string, create: () => FrameInput): Promise<FrameInput> {
  let input = inputs.get(key)
  if (!input) {
    input = create()
    inputs.set(key, input)
  }
  await StandaloneServices.get(IEditorService).openEditor(input, { pinned: true })
  return input
}

// Dashboards and other web tools.
class ToolInput extends FrameInput {
  constructor(url: string, name: string) {
    super(`tool:${url}`, name, false)
    this.frame.src = url
  }
}

export async function openTool(url: string, name: string): Promise<void> {
  await openFrame(`tool:${url}`, () => new ToolInput(url, name))
}

type StartSwingApp = (frame: HTMLIFrameElement, onExit: () => void) => Promise<SwingApp>

class SwingToolInput extends FrameInput {
  private readonly app: Promise<SwingApp>

  constructor(id: string, name: string, start: StartSwingApp, onStartError: (error: unknown) => void) {
    super(`swing:${id}`, name, true)
    this.app = start(this.frame, () => this.close())
    this.app.catch((error) => {
      onStartError(error)
      this.close()
    })
  }

  override dispose(): void {
    const frame = this.frame
    void this.app.then((app) => app.dispose(), () => undefined).finally(() => frame.remove())
    super.dispose()
  }
}

// Runs a Java Swing app in an editor tab. Opening an app that is already open shows its tab.
export async function openSwingTool(
  id: string,
  name: string,
  start: StartSwingApp,
  onStartError: (error: unknown) => void
): Promise<void> {
  await openFrame(`swing:${id}`, () => new SwingToolInput(id, name, start, onStartError))
}
