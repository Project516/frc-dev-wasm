// Key events inside a tool's iframe never reach the workbench. This replays the
// chords a tool leaves alone on the iframe element, where the workbench listens.
const MODIFIER_KEYS = new Set(['Control', 'Shift', 'Alt', 'AltGraph', 'Meta'])
// Chords for text editing keep their meaning inside the tool.
const EDITING_KEYS = new Set(['a', 'c', 'v', 'x', 'y', 'z'])
// Swing apps use most other Ctrl chords for their own menus, and CheerpJ gives no sign of which.
const SWING_PLAIN_KEYS = new Set(['b', 'j'])

// A frame keeps its window proxy when it navigates, so what is tracked is the document.
const attached = new WeakSet<Document>()

function isAlwaysForwarded(e: KeyboardEvent): boolean {
  if (e.key === 'F1') return true
  const mod = e.ctrlKey || e.metaKey
  const key = e.key.toLowerCase()
  return mod && ((e.shiftKey && key === 'p') || (!e.shiftKey && (key === 'p' || e.key === '`')))
}

function isForwarded(e: KeyboardEvent, swing: boolean): boolean {
  if (e.isComposing || e.altKey || MODIFIER_KEYS.has(e.key) || e.getModifierState('AltGraph')) return false
  if (isAlwaysForwarded(e)) return true
  if (!(e.ctrlKey || e.metaKey) || e.defaultPrevented) return false
  const key = e.key.toLowerCase()
  if (EDITING_KEYS.has(key)) return false
  return swing ? e.shiftKey || SWING_PLAIN_KEYS.has(key) : true
}

function replay(frame: HTMLIFrameElement, e: KeyboardEvent): void {
  const copy = new KeyboardEvent('keydown', {
    key: e.key,
    code: e.code,
    keyCode: e.keyCode,
    ctrlKey: e.ctrlKey,
    shiftKey: e.shiftKey,
    altKey: e.altKey,
    metaKey: e.metaKey,
    repeat: e.repeat,
    bubbles: true,
    cancelable: true,
    composed: true
  })
  frame.dispatchEvent(copy)
  if (copy.defaultPrevented) {
    e.preventDefault()
    e.stopPropagation()
  }
}

// A tool can take the focus back right after a key it did not get, which closes the command palette
// the key opened. While the workbench has the focus, the tool gets it only from the user or the IDE.
function guardFocus(frame: HTMLIFrameElement, win: Window & typeof globalThis): void {
  const proto = win.HTMLElement.prototype
  const focus = proto.focus
  proto.focus = function (this: HTMLElement, options?: FocusOptions) {
    const workbench = frame.ownerDocument
    if (workbench.hasFocus() && workbench.activeElement !== frame) return
    focus.call(this, options)
  }
}

/** Forwards workbench keybindings pressed inside a same-origin tool iframe. `swing` is for CheerpJ displays. */
export function forwardKeys(frame: HTMLIFrameElement, swing: boolean): void {
  const attach = () => {
    try {
      const win = frame.contentWindow as (Window & typeof globalThis) | null
      if (!win || attached.has(win.document)) return
      attached.add(win.document)
      guardFocus(frame, win)
      // The chords the workbench owns are taken before the tool sees them. The rest are
      // taken afterwards, unless the tool used them (Swing cannot tell, so it sees them first).
      win.addEventListener(
        'keydown',
        (e) => {
          if (isForwarded(e, swing) && (swing || isAlwaysForwarded(e))) replay(frame, e)
        },
        true
      )
      if (!swing) {
        win.addEventListener('keydown', (e) => {
          if (isForwarded(e, swing) && !isAlwaysForwarded(e)) replay(frame, e)
        })
      }
    } catch {
      // Cross-origin page, which cannot be scripted.
    }
  }
  attach()
  frame.addEventListener('load', attach)
}
