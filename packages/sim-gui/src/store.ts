import { signal, type Signal } from '@preact/signals';
import type { BridgeMessage, SimHost, SimStatus } from '@frc-dev-wasm/runtime';
import { DriverStation } from './ds.ts';
import { Joysticks } from './joysticks.ts';
import { NtModel } from './nt.ts';
import { protocolFor, type Protocol } from './protocol.ts';

export interface Device {
  type: string;
  device: string;
  data: Record<string, unknown>;
}

const FLUSH_MS = 50;
// One robot loop period.
const STEP_US = 20_000;
// Types the GUI does not show as devices.
const NOT_DEVICES = new Set(['DriverStation', 'Joystick', 'HAL', 'hello', 'stop', 'console', 'exit', 'sim.timing']);

export const deviceKey = (type: string, device: string) => `${type}\u0000${device}`;

export class SimStore {
  readonly devices = signal<ReadonlyMap<string, Device>>(new Map());
  readonly status = signal<SimStatus>('stopped');
  readonly paused = signal(false);
  /** Message keys of the project's WPILib year, read again when a robot program starts. */
  readonly protocol: Signal<Protocol>;
  /** Joystick LED colors (0xRRGGBB) the robot program set, by slot. */
  readonly joystickLeds = signal<number[]>([]);
  readonly ds: DriverStation;
  readonly joysticks: Joysticks;
  readonly nt: NtModel;

  private table = new Map<string, Device>();
  private flushTimer?: ReturnType<typeof setTimeout>;
  private readonly disposers: (() => void)[] = [];

  constructor(readonly host: SimHost) {
    this.protocol = signal(protocolFor(host.year()));
    this.nt = new NtModel(host);
    this.ds = new DriverStation(
      (data) => host.send({ type: 'DriverStation', device: '', data }),
      this.paused,
      () => this.protocol.peek(),
    );
    this.joysticks = new Joysticks(
      this.ds,
      (slot, data) => host.send({ type: 'Joystick', device: String(slot), data }),
      () => host.send({ type: 'DriverStation', device: '', data: { '>new_data': true } }),
      () => this.protocol.peek(),
    );
    this.status.value = host.status();
    this.loadSnapshot(true);
    this.devices.value = new Map(this.table);
    this.disposers.push(
      host.listen((messages) => this.receive(messages)),
      host.onStatus((status) => this.onStatus(status)),
      this.joysticks.attach(window),
      () => this.nt.dispose(),
    );
    if (this.status.peek() === 'running') this.sendInitialState();
  }

  dispose(): void {
    clearTimeout(this.flushTimer);
    for (const dispose of this.disposers) dispose();
  }

  /** A GUI that is already open keeps its own driver station state over the robot's defaults. */
  private loadSnapshot(adoptDriverStation: boolean): void {
    this.table.clear();
    for (const message of this.host.snapshot()) {
      if (adoptDriverStation || message.type !== 'DriverStation') this.apply(message);
    }
    this.queueFlush();
  }

  private onStatus(status: SimStatus): void {
    this.status.value = status;
    if (status !== 'stopped') this.protocol.value = protocolFor(this.host.year());
    if (status === 'running') {
      this.loadSnapshot(false);
      this.nt.refresh();
      this.sendInitialState();
    } else if (status === 'stopped') {
      this.table.clear();
      this.joystickLeds.value = [];
      this.ds.clearOpModes();
      this.paused.value = false;
      this.nt.refresh();
      this.queueFlush();
    }
  }

  /** Gives a new robot program the current driver station and joystick state. */
  private sendInitialState(): void {
    this.ds.resend();
    this.joysticks.reset();
  }

  private apply(message: BridgeMessage): void {
    switch (message.type) {
      case 'sim.timing':
        if (typeof message.data.paused === 'boolean') this.paused.value = message.data.paused;
        return;
      case 'DriverStation':
        this.ds.receive(message.data);
        return;
      case 'Joystick': {
        const leds = message.data['<leds'];
        const slot = Number(message.device);
        if (typeof leds === 'number' && Number.isInteger(slot) && this.joystickLeds.peek()[slot] !== leds) {
          const next = [...this.joystickLeds.peek()];
          next[slot] = leds;
          this.joystickLeds.value = next;
        }
        return;
      }
    }
    if (NOT_DEVICES.has(message.type) || message.type.startsWith('nt.')) return;
    const key = deviceKey(message.type, message.device);
    if (message.data['<init'] === false) {
      this.table.delete(key);
      return;
    }
    // Devices are replaced, not mutated: components with hook state skip re-renders when props are identical.
    const current = this.table.get(key);
    this.table.set(key, {
      type: message.type,
      device: message.device,
      data: { ...current?.data, ...message.data },
    });
  }

  private receive(messages: BridgeMessage[]): void {
    for (const message of messages) this.apply(message);
    this.queueFlush();
  }

  private queueFlush(): void {
    if (this.flushTimer !== undefined) return;
    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined;
      this.devices.value = new Map(this.table);
    }, FLUSH_MS);
  }

  /** Sends device inputs to the robot program and shows them locally, since the robot does not echo them. */
  setInputs(type: string, device: string, data: Record<string, unknown>): void {
    this.host.send({ type, device, data });
    this.apply({ type, device, data });
    this.queueFlush();
  }

  timing(op: 'pause' | 'resume' | 'step'): void {
    if (op === 'step' && !this.paused.peek()) this.timing('pause');
    const data = op === 'step' ? { op, us: STEP_US } : { op };
    this.host.send({ type: 'sim.timing', device: '', data });
    if (op === 'pause') this.paused.value = true;
    if (op === 'resume') this.paused.value = false;
  }
}
