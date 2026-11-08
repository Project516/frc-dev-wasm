// Keyboard joysticks as in halsim_gui's DriverStationGui.cpp. Keys are
// KeyboardEvent.code values; '' means unbound.

export interface AxisConfig {
  inc: string;
  dec: string;
  rate: number;
  decay: number;
  max: number;
}

export interface KeyboardConfig {
  axes: AxisConfig[];
  buttons: string[];
  /** Eight keys per POV, for 0, 45, 90 ... 315 degrees. */
  povs: string[][];
}

export const POV_ANGLES = [0, 45, 90, 135, 180, 225, 270, 315];
export const MAX_AXES = 12;

export function newAxis(inc = '', dec = ''): AxisConfig {
  return { inc, dec, rate: 0.05, decay: 0.05, max: 1 };
}

export function newPov(): string[] {
  return POV_ANGLES.map(() => '');
}

export function defaultKeyboards(): KeyboardConfig[] {
  return [
    {
      axes: [newAxis('KeyD', 'KeyA'), newAxis('KeyS', 'KeyW'), { ...newAxis('KeyR', 'KeyE'), rate: 0.01, decay: 0 }],
      buttons: ['KeyZ', 'KeyX', 'KeyC', 'KeyV'],
      povs: [['Numpad8', 'Numpad9', 'Numpad6', 'Numpad3', 'Numpad2', 'Numpad1', 'Numpad4', 'Numpad7']],
    },
    {
      axes: [newAxis('KeyL', 'KeyJ'), newAxis('KeyK', 'KeyI')],
      buttons: ['KeyM', 'Comma', 'Period', 'Slash'],
      povs: [],
    },
    {
      axes: [newAxis('ArrowRight', 'ArrowLeft'), newAxis('ArrowDown', 'ArrowUp')],
      buttons: ['Insert', 'Home', 'PageUp', 'Delete', 'End', 'PageDown'],
      povs: [],
    },
    { axes: [], buttons: [], povs: [] },
  ];
}

const NAMES: Record<string, string> = {
  ArrowUp: 'Up',
  ArrowDown: 'Down',
  ArrowLeft: 'Left',
  ArrowRight: 'Right',
  PageUp: 'PgUp',
  PageDown: 'PgDn',
  Comma: ',',
  Period: '.',
  Slash: '/',
  Backslash: '\\',
  Semicolon: ';',
  Quote: "'",
  BracketLeft: '[',
  BracketRight: ']',
  Minus: '-',
  Equal: '=',
  Backquote: '`',
  Space: 'Space',
};

export function keyName(code: string): string {
  if (!code) return '(none)';
  if (NAMES[code]) return NAMES[code];
  if (code.startsWith('Key')) return code.slice(3);
  if (code.startsWith('Digit')) return code.slice(5);
  if (code.startsWith('Numpad')) return `Num ${code.slice(6)}`;
  return code;
}
