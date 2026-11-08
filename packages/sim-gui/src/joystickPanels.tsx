import { useState } from 'preact/hooks';
import { useStore } from './context.ts';
import { MAX_AXES, POV_ANGLES, keyName, newAxis, newPov, type KeyboardConfig } from './keyboard.ts';
import { KEYBOARD_INDEX, SLOTS, type JoyData, type SystemJoystick } from './joysticks.ts';
import { Bar, Check, NumInput, Panel } from './ui.tsx';

const DRAG_TYPE = 'application/x-frc-joystick';

export function SystemJoysticks() {
  const { joysticks } = useStore();
  const [settings, setSettings] = useState<number | null>(null);
  return (
    <Panel id="system-joysticks" title="System Joysticks">
      <p class="hint">Drag onto a Joystick, or pick one there. Keyboard joysticks need this page focused.</p>
      <ul class="list">
        {joysticks.system.value.filter((joy) => joy.present || joy.keyboard).map((joy) => (
          <li>
            <div
              class={`list-row${joy.pressed ? ' pressed' : ''}${joy.present ? '' : ' absent'}`}
              draggable={joy.present}
              onDragStart={(e) => {
                e.dataTransfer?.setData(DRAG_TYPE, joy.id);
                e.dataTransfer?.setData('text/plain', joy.name);
              }}
              title={joy.name}
            >
              <span class="grow">
                {joy.index}: {joy.name}
              </span>
              {joy.pressed ? <span class="tag">active</span> : null}
              {joy.keyboard ? (
                <button
                  type="button"
                  class="btn small"
                  aria-expanded={settings === joy.index}
                  onClick={() => setSettings(settings === joy.index ? null : joy.index)}
                >
                  Settings
                </button>
              ) : null}
            </div>
            {joy.keyboard && settings === joy.index ? <KeyboardSettings index={joy.index - KEYBOARD_INDEX} /> : null}
          </li>
        ))}
      </ul>
      {joysticks.system.value.some((joy) => joy.present && !joy.keyboard) ? null : (
        <p class="hint">No gamepads detected. Press a button on a connected gamepad.</p>
      )}
    </Panel>
  );
}

function KeyButton({ id, kb, code, assign }: { id: string; kb: number; code: string; assign: (c: KeyboardConfig, key: string) => void }) {
  const { joysticks } = useStore();
  const waiting = joysticks.capturing.value === id;
  const bind = (key: string) => {
    if (key) joysticks.clearKey(key);
    joysticks.updateKeyboard(kb, (c) => assign(c, key));
  };
  return (
    <span class="inline">
      <button
        type="button"
        class="btn small key"
        aria-label={`Bind ${id.replaceAll(':', ' ')}, currently ${keyName(code)}`}
        onClick={() => (waiting ? joysticks.cancelCapture() : joysticks.beginCapture(id, bind))}
      >
        {waiting ? 'Press key' : keyName(code)}
      </button>
      <button type="button" class="btn small" disabled={!code} aria-label={`Clear ${id.replaceAll(':', ' ')}`} onClick={() => bind('')}>
        Clear
      </button>
    </span>
  );
}

function resize<T>(list: T[], count: number, make: () => T): void {
  while (list.length < count) list.push(make());
  list.length = count;
}

function KeyboardSettings({ index }: { index: number }) {
  const { joysticks, protocol } = useStore();
  const { maxButtons, maxPovs } = protocol.value;
  const config = joysticks.keyboards.value[index];
  const id = (...parts: (string | number)[]) => [index, ...parts].join(':');
  const edit = (change: (c: KeyboardConfig) => void) => joysticks.updateKeyboard(index, change);
  return (
    <div class="settings">
      <h3>Axes</h3>
      <div class="row">
        <span>Count</span>
        <NumInput label="Axis count" value={config.axes.length} min={0} max={MAX_AXES} step={1} onCommit={(n) => edit((c) => resize(c.axes, Math.max(0, Math.min(MAX_AXES, Math.round(n))), newAxis))} />
      </div>
      {config.axes.map((axis, i) => (
        <fieldset>
          <legend>Axis {i}</legend>
          <div class="row">
            <span>Increase</span>
            <KeyButton id={id('axis', i, 'increase')} kb={index} code={axis.inc} assign={(c, k) => (c.axes[i].inc = k)} />
          </div>
          <div class="row">
            <span>Decrease</span>
            <KeyButton id={id('axis', i, 'decrease')} kb={index} code={axis.dec} assign={(c, k) => (c.axes[i].dec = k)} />
          </div>
          <div class="row">
            <span>Key rate</span>
            <NumInput label={`Axis ${i} key rate`} value={axis.rate} onCommit={(n) => edit((c) => (c.axes[i].rate = n))} />
          </div>
          <div class="row">
            <span>Decay rate</span>
            <NumInput label={`Axis ${i} decay rate`} value={axis.decay} onCommit={(n) => edit((c) => (c.axes[i].decay = n))} />
          </div>
          <div class="row">
            <span>Max absolute value</span>
            <NumInput label={`Axis ${i} max absolute value`} value={axis.max} min={-1} max={1} onCommit={(n) => edit((c) => (c.axes[i].max = Math.max(-1, Math.min(1, n))))} />
          </div>
        </fieldset>
      ))}
      <h3>Buttons</h3>
      <div class="row">
        <span>Count</span>
        <NumInput label="Button count" value={config.buttons.length} min={0} max={maxButtons} step={1} onCommit={(n) => edit((c) => resize(c.buttons, Math.max(0, Math.min(maxButtons, Math.round(n))), () => ''))} />
      </div>
      {config.buttons.map((code, i) => (
        <div class="row">
          <span>Button {i + 1}</span>
          <KeyButton id={id('button', i + 1)} kb={index} code={code} assign={(c, k) => (c.buttons[i] = k)} />
        </div>
      ))}
      <h3>POVs</h3>
      <div class="row">
        <span>Count</span>
        <NumInput label="POV count" value={config.povs.length} min={0} max={maxPovs} step={1} onCommit={(n) => edit((c) => resize(c.povs, Math.max(0, Math.min(maxPovs, Math.round(n))), newPov))} />
      </div>
      {config.povs.map((keys, i) => (
        <fieldset>
          <legend>POV {i}</legend>
          {POV_ANGLES.map((angle, a) => (
            <div class="row">
              <span>{angle} deg</span>
              <KeyButton id={id('pov', i, angle)} kb={index} code={keys[a]} assign={(c, k) => (c.povs[i][a] = k)} />
            </div>
          ))}
        </fieldset>
      ))}
    </div>
  );
}

function SlotView({ slot, joy, data }: { slot: number; joy: SystemJoystick | undefined; data: JoyData }) {
  const { joysticks, protocol, joystickLeds } = useStore();
  const leds = joystickLeds.value[slot] ?? 0;
  const ledColor = `#${(leds & 0xffffff).toString(16).padStart(6, '0')}`;
  const [over, setOver] = useState(false);
  const assigned = joysticks.slots.value[slot];
  const present = joysticks.system.value.filter((j) => j.present);
  const known = present.some((j) => j.id === assigned.id);
  return (
    <div
      class={`slot${over ? ' over' : ''}`}
      onDragOver={(e) => {
        if (e.dataTransfer?.types.includes(DRAG_TYPE)) {
          e.preventDefault();
          setOver(true);
        }
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        setOver(false);
        const id = e.dataTransfer?.getData(DRAG_TYPE);
        if (id) {
          e.preventDefault();
          joysticks.assign(slot, id);
        }
      }}
    >
      <div
        class="slot-head"
        draggable={!!assigned.id}
        onDragStart={(e) => assigned.id && e.dataTransfer?.setData(DRAG_TYPE, assigned.id)}
      >
        <label for={`slot-${slot}`}>Joystick {slot}</label>
        <select id={`slot-${slot}`} value={assigned.id ?? ''} onChange={(e) => joysticks.assign(slot, e.currentTarget.value || null)}>
          <option value="">Unassigned</option>
          {assigned.id && !known ? <option value={assigned.id}>{assigned.id} (not connected)</option> : null}
          {present.map((j) => (
            <option value={j.id}>
              {j.index}: {j.name}
            </option>
          ))}
        </select>
      </div>
      {assigned.id && joy ? (
        <div class="slot-body">
          {joy.gamepad ? <Check checked={assigned.map} onChange={(v) => joysticks.setMap(slot, v)} label="Map gamepad" /> : null}
          {data.axes.map((v, i) => (
            <div class="axis">
              <span>Axis[{i}]</span>
              <Bar value={v} min={-1} max={1} label={`Joystick ${slot} axis ${i}`} />
              <span class="value">{v.toFixed(3)}</span>
            </div>
          ))}
          {data.povs.map((v, i) => (
            <div class="axis">
              <span>POV[{i}]</span>
              <span />
              <span class="value">{v}</span>
            </div>
          ))}
          {protocol.value.joystickLeds ? (
            <div class="axis">
              <span>LEDs</span>
              <span class="swatch" style={{ background: ledColor, width: '100%', border: '1px solid var(--sg-border)' }} role="img" aria-label={`Joystick ${slot} LED color ${ledColor}`} />
              <span class="value">{ledColor.slice(1)}</span>
            </div>
          ) : null}
          <div class="buttons" role="group" aria-label={`Joystick ${slot} buttons`}>
            {data.buttons.map((down, i) => (
              <span class={`btn-led${down ? ' on' : ''}`} role="img" aria-label={`Button ${i + 1} ${down ? 'pressed' : 'released'}`}>
                {i + 1}
              </span>
            ))}
          </div>
        </div>
      ) : assigned.id ? (
        <p class="hint">Not connected</p>
      ) : null}
    </div>
  );
}

export function Joysticks() {
  const { joysticks } = useStore();
  const system = joysticks.system.value;
  const slots = joysticks.slots.value;
  const data = joysticks.data.value;
  return (
    <Panel id="joysticks" title="Joysticks" full>
      <div class="slots">
        {Array.from({ length: SLOTS }, (_, i) => (
          <SlotView slot={i} joy={system.find((j) => j.id === slots[i].id && j.present)} data={data[i]} />
        ))}
      </div>
    </Panel>
  );
}
