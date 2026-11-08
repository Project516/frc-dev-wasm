import { effect, signal } from '@preact/signals';
import type { DriverStation } from './ds.ts';
import { MAX_AXES, POV_ANGLES, defaultKeyboards, type KeyboardConfig } from './keyboard.ts';
import { joystickInputs, type Protocol } from './protocol.ts';
import { persisted } from './storage.ts';

export const SLOTS = 6;
/** Index shown for the first keyboard joystick, after the 16 GLFW joystick slots. */
export const KEYBOARD_INDEX = 16;
const KEYBOARD_STEP_MS = 1000 / 60;
const DISPLAY_MS = 50;

export interface JoyData {
  axes: number[];
  buttons: boolean[];
  povs: number[];
}

export interface SystemJoystick {
  /** Stable id used to remember assignments: the gamepad id string or `KeyboardN`. */
  id: string;
  index: number;
  name: string;
  present: boolean;
  pressed: boolean;
  gamepad: boolean;
  keyboard: boolean;
}

export interface Slot {
  id: string | null;
  /** Use the XInput style layout (halsim_gui's "Map gamepad"). */
  map: boolean;
}

const emptyData = (): JoyData => ({ axes: [], buttons: [], povs: [] });
const round = (v: number) => Math.round(v * 1000) / 1000;

function povAngle(up: boolean, right: boolean, down: boolean, left: boolean): number {
  const x = (right ? 1 : 0) - (left ? 1 : 0);
  const y = (up ? 1 : 0) - (down ? 1 : 0);
  if (x === 0 && y === 0) return -1;
  return (Math.round((Math.atan2(x, y) * 180) / Math.PI) + 360) % 360;
}

/** Standard layout to WPILib Xbox order: LeftX, LeftY, LT, RT, RightX, RightY; A B X Y LB RB Back Start LS RS. */
function gamepadData(g: Gamepad, map: boolean, maxButtons: number): JoyData {
  if (map && g.mapping === 'standard') {
    const b = g.buttons;
    const down = (i: number) => b[i]?.pressed ?? false;
    return {
      axes: [g.axes[0], g.axes[1], b[6]?.value ?? 0, b[7]?.value ?? 0, g.axes[2], g.axes[3]].map(round),
      buttons: [0, 1, 2, 3, 4, 5, 8, 9, 10, 11].map(down),
      povs: [povAngle(down(12), down(15), down(13), down(14))],
    };
  }
  return {
    axes: g.axes.slice(0, MAX_AXES).map(round),
    buttons: g.buttons.slice(0, maxButtons).map((b) => b.pressed),
    povs: [],
  };
}

function validKeyboards(value: unknown): value is KeyboardConfig[] {
  return (
    Array.isArray(value) &&
    value.length === 4 &&
    value.every((k) => k && Array.isArray(k.axes) && Array.isArray(k.buttons) && Array.isArray(k.povs))
  );
}

function isEditable(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  return !!el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable);
}

/** System joysticks (gamepads and keyboards), their assignment to robot slots, and the Joystick messages. */
export class Joysticks {
  readonly system = signal<SystemJoystick[]>([]);
  readonly slots = persisted<Slot[]>(
    'joystick.slots',
    Array.from({ length: SLOTS }, () => ({ id: null, map: false })),
  );
  readonly data = signal<JoyData[]>(Array.from({ length: SLOTS }, emptyData));
  readonly keyboards = persisted<KeyboardConfig[]>('keyboards', defaultKeyboards());
  /** Id of the key binding waiting for a key press, if any. */
  readonly capturing = signal<string | null>(null);

  private readonly pressed = new Set<string>();
  private readonly axisValues: number[][] = [[], [], [], []];
  private readonly lastSent: (string | undefined)[] = [];
  private shown: string[] = [];
  private systemKey = '';
  private lastDisplay = 0;
  private lastFrame = 0;
  private accumulated = 0;
  private onKey?: (code: string) => void;

  constructor(
    private readonly ds: DriverStation,
    private readonly sendJoystick: (slot: number, data: Record<string, unknown>) => void,
    private readonly sendNewData: () => void,
    private readonly protocol: () => Protocol,
  ) {
    if (!validKeyboards(this.keyboards.peek())) this.keyboards.value = defaultKeyboards();
    if (this.slots.peek().length !== SLOTS) {
      this.slots.value = Array.from({ length: SLOTS }, (_, i) => this.slots.peek()[i] ?? { id: null, map: false });
    }
    effect(() => {
      if (this.ds.attached.value) this.reset();
    });
  }

  /** Forgets what was sent, so the next frame sends every slot again. */
  reset(): void {
    this.lastSent.length = 0;
  }

  assign(slot: number, id: string | null): void {
    const gamepad = this.system.peek().find((j) => j.id === id)?.gamepad ?? false;
    const slots = this.slots.peek().map((s) => (id !== null && s.id === id ? { id: null, map: false } : s));
    slots[slot] = { id, map: gamepad };
    this.slots.value = slots;
  }

  setMap(slot: number, map: boolean): void {
    this.slots.value = this.slots.peek().map((s, i) => (i === slot ? { ...s, map } : s));
  }

  updateKeyboard(index: number, edit: (config: KeyboardConfig) => void): void {
    const all = structuredClone(this.keyboards.peek());
    edit(all[index]);
    this.keyboards.value = all;
  }

  clearKey(code: string): void {
    const all = structuredClone(this.keyboards.peek());
    for (const k of all) {
      for (const a of k.axes) {
        if (a.inc === code) a.inc = '';
        if (a.dec === code) a.dec = '';
      }
      k.buttons = k.buttons.map((c) => (c === code ? '' : c));
      k.povs = k.povs.map((keys) => keys.map((c) => (c === code ? '' : c)));
    }
    this.keyboards.value = all;
  }

  beginCapture(id: string, assign: (code: string) => void): void {
    this.capturing.value = id;
    this.onKey = assign;
  }

  cancelCapture(): void {
    this.capturing.value = null;
    this.onKey = undefined;
  }

  private isBound(code: string): boolean {
    return this.keyboards.peek().some(
      (k) =>
        k.buttons.includes(code) ||
        k.axes.some((a) => a.inc === code || a.dec === code) ||
        k.povs.some((keys) => keys.includes(code)),
    );
  }

  /** Starts keyboard tracking and polling on `win`. Polling only runs while the page is visible. */
  attach(win: Window): () => void {
    const doc = win.document;
    const down = (e: KeyboardEvent) => {
      if (this.onKey) {
        e.preventDefault();
        if (e.code === 'Escape') this.cancelCapture();
        else {
          const assign = this.onKey;
          this.cancelCapture();
          assign(e.code);
        }
        return;
      }
      if (isEditable(e.target) || e.ctrlKey || e.metaKey || e.altKey) return;
      if (this.isBound(e.code)) e.preventDefault();
      this.pressed.add(e.code);
    };
    const up = (e: KeyboardEvent) => this.pressed.delete(e.code);
    let raf = 0;
    const loop = (now: number) => {
      this.frame(now);
      raf = win.requestAnimationFrame(loop);
    };
    const visibility = () => {
      win.cancelAnimationFrame(raf);
      if (doc.visibilityState === 'visible') {
        this.lastFrame = 0;
        raf = win.requestAnimationFrame(loop);
      } else {
        this.pressed.clear();
      }
    };
    win.addEventListener('keydown', down);
    win.addEventListener('keyup', up);
    const blur = () => this.pressed.clear();
    win.addEventListener('blur', blur);
    doc.addEventListener('visibilitychange', visibility);
    visibility();
    return () => {
      win.cancelAnimationFrame(raf);
      win.removeEventListener('keydown', down);
      win.removeEventListener('keyup', up);
      win.removeEventListener('blur', blur);
      doc.removeEventListener('visibilitychange', visibility);
    };
  }

  private stepKeyboards(): void {
    const keyboards = this.keyboards.peek();
    keyboards.forEach((k, n) => {
      const values = this.axisValues[n];
      values.length = k.axes.length;
      k.axes.forEach((axis, i) => {
        let v = values[i] ?? 0;
        if (this.pressed.has(axis.inc)) v = Math.min(v + axis.rate, axis.max);
        else if (v > 0) v = v < axis.decay ? 0 : v - axis.decay;
        if (this.pressed.has(axis.dec)) v = Math.max(v - axis.rate, -axis.max);
        else if (v < 0) v = v > -axis.decay ? 0 : v + axis.decay;
        values[i] = v;
      });
    });
  }

  private keyboardData(n: number): JoyData {
    const k = this.keyboards.peek()[n];
    const { maxButtons, maxPovs } = this.protocol();
    return {
      axes: this.axisValues[n].slice(0, MAX_AXES).map(round),
      buttons: k.buttons.slice(0, maxButtons).map((code) => this.pressed.has(code)),
      povs: k.povs.slice(0, maxPovs).map((keys) => {
        const i = keys.findIndex((code) => code && this.pressed.has(code));
        return i < 0 ? -1 : POV_ANGLES[i];
      }),
    };
  }

  private frame(now: number): void {
    this.accumulated = Math.min(this.accumulated + (this.lastFrame ? now - this.lastFrame : 0), 100);
    this.lastFrame = now;
    while (this.accumulated >= KEYBOARD_STEP_MS) {
      this.stepKeyboards();
      this.accumulated -= KEYBOARD_STEP_MS;
    }

    const pads = navigator.getGamepads?.() ?? [];
    const list: SystemJoystick[] = [];
    const count = Math.max(SLOTS, pads.length);
    for (let i = 0; i < count; i++) {
      const g = pads[i];
      list.push(
        g
          ? {
              id: g.id,
              index: i,
              name: g.id,
              present: true,
              pressed: g.buttons.some((b) => b.pressed),
              gamepad: g.mapping === 'standard',
              keyboard: false,
            }
          : { id: '', index: i, name: '(null)', present: false, pressed: false, gamepad: false, keyboard: false },
      );
    }
    this.keyboards.peek().forEach((k, n) => {
      const data = this.keyboardData(n);
      list.push({
        id: `Keyboard${n}`,
        index: KEYBOARD_INDEX + n,
        name: `Keyboard ${n}`,
        present: k.axes.length + k.buttons.length + k.povs.length > 0,
        pressed: data.buttons.some(Boolean) || data.povs.some((p) => p >= 0),
        gamepad: false,
        keyboard: true,
      });
    });
    const systemKey = JSON.stringify(list);
    if (systemKey !== this.systemKey) {
      this.systemKey = systemKey;
      this.system.value = list;
    }

    const slots = this.slots.peek();
    const data = slots.map((slot): JoyData => {
      if (!slot.id) return emptyData();
      if (slot.id.startsWith('Keyboard')) {
        const n = Number(slot.id.slice(8));
        return this.keyboards.peek()[n] ? this.keyboardData(n) : emptyData();
      }
      const pad = pads.find((g) => g?.id === slot.id);
      return pad ? gamepadData(pad, slot.map, this.protocol().maxButtons) : emptyData();
    });
    const keys = data.map((d) => JSON.stringify(d));

    if (now - this.lastDisplay >= DISPLAY_MS && keys.some((key, i) => key !== this.shown[i])) {
      this.shown = keys;
      this.lastDisplay = now;
      this.data.value = data;
    }

    // halsim_gui leaves joystick data alone while the robot is in autonomous.
    if (!this.ds.attached.peek() || this.ds.autonomous.peek()) return;
    let sent = false;
    keys.forEach((key, slot) => {
      if (key === this.lastSent[slot]) return;
      this.lastSent[slot] = key;
      this.sendJoystick(slot, joystickInputs(this.protocol(), data[slot]));
      sent = true;
    });
    if (sent) this.sendNewData();
  }
}
