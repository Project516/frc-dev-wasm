import { useEffect, useRef } from 'preact/hooks';
import { useStore } from './context.ts';
import type { Device } from './store.ts';
import { Bar, Check, Led, NumInput, Panel, TextInput } from './ui.tsx';

const num = (v: unknown): number | undefined => (typeof v === 'number' ? v : undefined);
const byDevice = (a: Device, b: Device) => a.device.localeCompare(b.device, undefined, { numeric: true });
// The period halsim reports for an encoder that is not moving.
const STOPPED_PERIOD = Number.MAX_VALUE;
const BROWNOUT_VOLTS = 6.75;
// Pulse width bar range, and the output period in ms of each scale PWM.setOutputPeriod sends.
const PWM_MAX_MICROSECONDS = 2500;
const PWM_PERIOD_MS: Record<number, number> = { 0: 5, 1: 10, 3: 20 };

const KNOWN = new Set([
  'PWM',
  'DIO',
  'AI',
  'Encoder',
  'Relay',
  'Solenoid',
  'CTREPCM',
  'REVPH',
  'PowerDistribution',
  'PDChannel',
  'REVPHSolenoid',
  'RoboRIO',
  'AddressableLED',
]);

function useInputs() {
  const store = useStore();
  return (type: string, device: string, data: Record<string, unknown>) => store.setInputs(type, device, data);
}

function Pwm({ d }: { d: Device }) {
  if (!useStore().protocol.value.pwmSpeed) return <PwmPulse d={d} />;
  const speed = num(d.data['<speed']);
  const position = num(d.data['<position']);
  const servo = !speed && !!position;
  const value = servo ? position : (speed ?? 0);
  return (
    <div class="row">
      <span>PWM[{d.device}]</span>
      <span class="meter">
        <Bar value={value} min={servo ? 0 : -1} max={1} label={`PWM ${d.device}`} />
        <span class="value">{value.toFixed(3)}</span>
      </span>
    </div>
  );
}

function PwmPulse({ d }: { d: Device }) {
  const raw = num(d.data['<raw']) ?? 0;
  const period = PWM_PERIOD_MS[num(d.data['<output_period']) ?? -1];
  return (
    <div class="row">
      <span>
        PWM[{d.device}]
        {period ? (
          <>
            {' '}
            <span class="tag">{period} ms</span>
          </>
        ) : null}
      </span>
      <span class="meter">
        <Bar value={raw} min={0} max={PWM_MAX_MICROSECONDS} label={`PWM ${d.device}`} />
        <span class="value">{raw} us</span>
      </span>
    </div>
  );
}

function Dio({ d }: { d: Device }) {
  const setInputs = useInputs();
  const input = d.data['<input'] === true;
  const high = d.data['<>value'] === true;
  return (
    <div class="row">
      <span>
        DIO[{d.device}] <span class="tag">{input ? 'in' : 'out'}</span>
      </span>
      {input ? (
        <Check checked={high} label={high ? 'High' : 'Low'} onChange={(v) => setInputs('DIO', d.device, { '<>value': v })} />
      ) : (
        <span class="inline">
          <Led on={high} label={`DIO ${d.device}`} />
          <span class="value">{high ? 'High' : 'Low'}</span>
        </span>
      )}
    </div>
  );
}

const ANALOG_MAX_VOLTS = 5;

function Analog({ d }: { d: Device }) {
  const setInputs = useInputs();
  const volts = num(d.data['>voltage']) ?? 0;
  const set = (v: number) => setInputs('AI', d.device, { '>voltage': v });
  return (
    <div class="row">
      <label for={`ai-${d.device}`}>AI[{d.device}]</label>
      <span class="inline">
        <input
          id={`ai-${d.device}`}
          type="range"
          min={0}
          max={ANALOG_MAX_VOLTS}
          step={0.01}
          value={volts}
          onInput={(e) => set(e.currentTarget.valueAsNumber)}
        />
        <NumInput label={`Analog input ${d.device} volts`} value={volts} min={0} max={ANALOG_MAX_VOLTS} step={0.01} onCommit={set} />
      </span>
    </div>
  );
}

function Encoder({ d }: { d: Device }) {
  const setInputs = useInputs();
  const { encoderPeriod } = useStore().protocol.value;
  const count = num(d.data['>count']) ?? 0;
  const period = num(d.data['>period']) ?? STOPPED_PERIOD;
  const rate = encoderPeriod ? (period > 0 && period < 1e300 ? 1 / period : 0) : (num(d.data['>rate']) ?? 0);
  const name = `Encoder[${d.data['<channel_a'] ?? '?'},${d.data['<channel_b'] ?? '?'}]`;
  return (
    <fieldset>
      <legend>{name}</legend>
      <div class="row">
        <span>Count</span>
        <NumInput label={`${name} count`} value={count} step={1} onCommit={(n) => setInputs('Encoder', d.device, { '>count': Math.round(n) })} />
      </div>
      <div class="row">
        <span>{encoderPeriod ? 'Rate (pulses/s)' : 'Rate (distance/s)'}</span>
        <NumInput
          label={`${name} rate`}
          value={rate}
          onCommit={(n) =>
            setInputs('Encoder', d.device, encoderPeriod ? { '>period': n === 0 ? STOPPED_PERIOD : 1 / Math.abs(n) } : { '>rate': n })
          }
        />
      </div>
    </fieldset>
  );
}

function Relay({ d }: { d: Device }) {
  const fwd = d.data['<fwd'] === true;
  const rev = d.data['<rev'] === true;
  const hasFwd = d.data['<init_fwd'] === true;
  const hasRev = d.data['<init_rev'] === true;
  return (
    <div class="row">
      <span>Relay[{d.device}]</span>
      <span class="inline">
        {hasFwd ? (
          <>
            <Led on={fwd} label={`Relay ${d.device} forward`} />
            <span>Fwd</span>
          </>
        ) : null}
        {hasRev ? (
          <>
            <Led on={rev} label={`Relay ${d.device} reverse`} />
            <span>Rev</span>
          </>
        ) : null}
      </span>
    </div>
  );
}

function Rows({ d, skip = [] }: { d: Device; skip?: string[] }) {
  const setInputs = useInputs();
  const keys = Object.keys(d.data)
    .filter((k) => k !== '<init' && !skip.includes(k))
    .sort((a, b) => a.replace(/^[<>]+/, '').localeCompare(b.replace(/^[<>]+/, '')));
  return (
    <div class="rows">
      {keys.map((key) => {
        const name = key.replace(/^[<>]+/, '');
        const value = d.data[key];
        const editable = key.startsWith('>') || key.startsWith('<>');
        const set = (v: unknown) => setInputs(d.type, d.device, { [key]: v });
        let control;
        if (editable && typeof value === 'boolean') control = <Check checked={value} label={value ? 'True' : 'False'} onChange={set} />;
        else if (editable && typeof value === 'number') control = <NumInput label={name} value={value} onCommit={set} />;
        else if (editable && typeof value === 'string') control = <TextInput label={name} value={value} onCommit={set} />;
        else control = <span class="value">{typeof value === 'object' ? JSON.stringify(value) : String(value)}</span>;
        return (
          <div class="row">
            <span title={key.startsWith('<>') ? 'Input and output' : key.startsWith('>') ? 'Input to the robot' : 'Output from the robot'}>{name}</span>
            {control}
          </div>
        );
      })}
    </div>
  );
}

function Compressor({ d }: { d: Device }) {
  const setInputs = useInputs();
  return (
    <fieldset>
      <legend>PCM {d.device}</legend>
      <div class="row">
        <span>Compressor</span>
        <Check checked={d.data['>on'] === true} label="Running" onChange={(v) => setInputs('CTREPCM', d.device, { '>on': v })} />
      </div>
      <div class="row">
        <span>Closed loop</span>
        <span class="value">{d.data['<closed_loop'] === true ? 'Enabled' : 'Disabled'}</span>
      </div>
      <div class="row">
        <span>Pressure switch</span>
        <Check checked={d.data['>pressure_switch'] === true} label="Closed" onChange={(v) => setInputs('CTREPCM', d.device, { '>pressure_switch': v })} />
      </div>
      <div class="row">
        <span>Current (A)</span>
        <NumInput label={`PCM ${d.device} compressor current`} value={num(d.data['>current']) ?? 0} onCommit={(n) => setInputs('CTREPCM', d.device, { '>current': n })} />
      </div>
    </fieldset>
  );
}

function SolenoidModule({ module, channels }: { module: string; channels: Device[] }) {
  return (
    <fieldset>
      <legend>Solenoids, module {module}</legend>
      <div class="buttons">
        {channels.map((d) => {
          const on = d.data['<output'] === true;
          const channel = d.device.split(',')[1];
          return (
            <span class={`btn-led${on ? ' on' : ''}`} role="img" aria-label={`Solenoid ${module}, ${channel} ${on ? 'on' : 'off'}`}>
              {channel}
            </span>
          );
        })}
      </div>
    </fieldset>
  );
}

function RoboRio({ d }: { d: Device }) {
  const setInputs = useInputs();
  const { userButton } = useStore().protocol.value;
  const volts = num(d.data['>vin_voltage']) ?? 12;
  const brownout = volts < BROWNOUT_VOLTS;
  return (
    <>
      <div class="row">
        <label for="roborio-vin">Battery voltage</label>
        <span class="inline">
          <input
            id="roborio-vin"
            type="range"
            min={0}
            max={14}
            step={0.1}
            value={volts}
            onInput={(e) => setInputs('RoboRIO', d.device, { '>vin_voltage': e.currentTarget.valueAsNumber })}
          />
          <NumInput label="Battery voltage" value={volts} min={0} step={0.1} onCommit={(n) => setInputs('RoboRIO', d.device, { '>vin_voltage': n })} />
        </span>
      </div>
      <div class="row">
        <span>Brownout</span>
        <span class="inline">
          <Led on={brownout} label="Brownout" />
          <span class="value">{brownout ? 'Browned out' : 'OK'}</span>
        </span>
      </div>
      {userButton ? (
        <div class="row">
          <span>User button</span>
          <Check checked={d.data['>fpga_button'] === true} label={d.data['>fpga_button'] === true ? 'Pressed' : 'Released'} onChange={(v) => setInputs('RoboRIO', d.device, { '>fpga_button': v })} />
        </div>
      ) : null}
      <details>
        <summary>Rails</summary>
        <Rows d={d} skip={['>vin_voltage', '>fpga_button']} />
      </details>
    </>
  );
}

const LED_HEIGHT = 14;

function LedStrip({ d }: { d: Device }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const { ledRunning } = useStore().protocol.value;
  const data = d.data['<data'];
  const length = num(d.data['<length']) ?? (Array.isArray(data) ? data.length : 0);
  const running = !ledRunning || d.data['<running'] === true;
  useEffect(() => {
    const el = canvas.current;
    const ctx = el?.getContext('2d');
    if (!el || !ctx) return;
    const width = el.clientWidth;
    const scale = window.devicePixelRatio || 1;
    el.width = Math.round(width * scale);
    el.height = Math.round(LED_HEIGHT * scale);
    ctx.scale(scale, scale);
    ctx.clearRect(0, 0, width, LED_HEIGHT);
    if (!Array.isArray(data) || length === 0) return;
    const step = width / length;
    for (let i = 0; i < length; i++) {
      const c = data[i] as { r?: number; g?: number; b?: number } | undefined;
      ctx.fillStyle = c ? `rgb(${c.r ?? 0} ${c.g ?? 0} ${c.b ?? 0})` : '#000';
      ctx.fillRect(i * step, 0, Math.max(step - (step > 4 ? 1 : 0), 1), LED_HEIGHT);
    }
  }, [data, length]);
  const label = `LED strip ${d.device}, ${length} LEDs${running ? '' : ', not running'}`;
  return (
    <div class="strip">
      <span>
        LEDs[{d.device}] <span class="tag">{length}</span>
      </span>
      <canvas ref={canvas} class="led-canvas" role="img" aria-label={label} />
    </div>
  );
}

export function DevicePanels() {
  const { devices } = useStore();
  const all = [...devices.value.values()].sort(byDevice);
  const of = (type: string) => all.filter((d) => d.type === type);
  const pwm = of('PWM');
  const dio = of('DIO');
  const ai = of('AI');
  const encoders = of('Encoder');
  const relays = of('Relay');
  const pcms = of('CTREPCM');
  const phs = of('REVPH');
  const solenoids = of('Solenoid');
  const pdps = of('PowerDistribution');
  const rios = of('RoboRIO');
  const strips = of('AddressableLED');
  const other = all.filter((d) => !KNOWN.has(d.type));

  const modules = new Map<string, Device[]>();
  for (const d of solenoids) {
    const module = d.device.split(',')[0];
    modules.set(module, [...(modules.get(module) ?? []), d]);
  }
  const hasPneumatics = pcms.length + phs.length + solenoids.length > 0;

  return (
    <>
      {rios.length ? (
        <Panel id="roborio" title="RoboRIO">
          {rios.map((d) => (
            <RoboRio d={d} />
          ))}
        </Panel>
      ) : null}
      {pwm.length ? (
        <Panel id="pwm" title="PWM Outputs" badge={String(pwm.length)}>
          <div class="rows">{pwm.map((d) => <Pwm d={d} />)}</div>
        </Panel>
      ) : null}
      {dio.length ? (
        <Panel id="dio" title="Digital I/O" badge={String(dio.length)}>
          <div class="rows">{dio.map((d) => <Dio d={d} />)}</div>
        </Panel>
      ) : null}
      {ai.length ? (
        <Panel id="ai" title="Analog Inputs" badge={String(ai.length)}>
          <div class="rows">{ai.map((d) => <Analog d={d} />)}</div>
        </Panel>
      ) : null}
      {encoders.length ? (
        <Panel id="encoders" title="Encoders" badge={String(encoders.length)}>
          {encoders.map((d) => (
            <Encoder d={d} />
          ))}
        </Panel>
      ) : null}
      {relays.length ? (
        <Panel id="relays" title="Relays" badge={String(relays.length)}>
          <div class="rows">{relays.map((d) => <Relay d={d} />)}</div>
        </Panel>
      ) : null}
      {hasPneumatics ? (
        <Panel id="pneumatics" title="Pneumatics">
          {pcms.map((d) => (
            <Compressor d={d} />
          ))}
          {phs.map((d) => (
            <fieldset>
              <legend>REV PH {d.device}</legend>
              <Rows d={d} />
            </fieldset>
          ))}
          {[...modules].map(([module, channels]) => (
            <SolenoidModule module={module} channels={channels} />
          ))}
        </Panel>
      ) : null}
      {pdps.length ? (
        <Panel id="power" title="Power Distribution">
          {pdps.map((d) => (
            <fieldset>
              <legend>PD {d.device}</legend>
              <Rows d={d} />
            </fieldset>
          ))}
        </Panel>
      ) : null}
      {strips.length ? (
        <Panel id="leds" title="Addressable LEDs">
          {strips.map((d) => (
            <LedStrip d={d} />
          ))}
        </Panel>
      ) : null}
      {other.length ? (
        <Panel id="other" title="Other Devices" badge={String(other.length)}>
          {other.map((d) => (
            <details open>
              <summary>
                {d.type === 'SimDevice' ? d.device : `${d.type}: ${d.device}`}
              </summary>
              <Rows d={d} />
            </details>
          ))}
        </Panel>
      ) : null}
    </>
  );
}
